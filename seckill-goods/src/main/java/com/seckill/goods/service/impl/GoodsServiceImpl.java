package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seckill.common.exception.BizException;
import com.seckill.common.redis.RedisKeys;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.cache.GoodsBloomFilter;
import com.seckill.goods.dto.GoodsDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.entity.SeckillActivity;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.mapper.GoodsMapper;
import com.seckill.goods.mapper.SeckillActivityMapper;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.goods.service.GoodsService;
import com.seckill.goods.vo.CachedGoodsVO;
import com.seckill.goods.vo.GoodsDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 商品服务（M3 起详情走"缓存三件套"）。
 * 缓存策略：Cache Aside（旁路缓存）——读：缓存命中直接返回；未命中查 DB 回填。写：先更新 DB 再删缓存。
 *
 * 三件套防护（面试必考，逐层对应）：
 * 1. 穿透（查不存在的数据）：布隆过滤器先拦截（一定不存在直接拒）+ 空值缓存兜底（DB 也没有的 ID 缓存空标记 60s）
 * 2. 击穿（热点 key 过期瞬间大量请求打到 DB）：逻辑过期 + SETNX 互斥重建——过期后只放一个请求进 DB，其余返回旧值
 * 3. 雪崩（大量 key 同一时刻过期）：TTL 随机化（30 分钟 ± 随机 5 分钟），错开过期时间点
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsServiceImpl implements GoodsService {

    /** 物理 TTL 基础值（分钟）：只作兜底，业务以逻辑过期为准 */
    private static final int CACHE_TTL_MINUTES = 30;

    /** 空值缓存 TTL：DB 确认不存在时缓存空标记，防止恶意/异常 ID 反复打 DB */
    private static final Duration EMPTY_TTL = Duration.ofSeconds(60);

    private final GoodsMapper goodsMapper;
    private final StockMapper stockMapper;
    private final SeckillActivityMapper activityMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GoodsBloomFilter bloomFilter;

    @Override
    @Transactional
    public Goods create(GoodsDTO dto) {
        Goods goods = new Goods();
        goods.setGoodsName(dto.getGoodsName());
        goods.setDescription(dto.getDescription());
        goods.setNormalPrice(dto.getNormalPrice());
        goods.setStatus(1);
        goodsMapper.insert(goods);

        // 秒杀库存独立成表：商品创建时初始化一行 0 库存，活动创建时再灌入
        Stock stock = new Stock();
        stock.setGoodsId(goods.getId());
        stock.setGoodsName(goods.getGoodsName());
        stock.setTotalStock(0);
        stock.setAvailableStock(0);
        stock.setSoldCount(0);
        stock.setVersion(0);
        stockMapper.insert(stock);

        // 新商品 ID 加入布隆过滤器，保持穿透防线与数据一致
        bloomFilter.add(goods.getId());
        return goods;
    }

    @Override
    public Goods update(Long id, GoodsDTO dto) {
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        goods.setGoodsName(dto.getGoodsName());
        goods.setDescription(dto.getDescription());
        goods.setNormalPrice(dto.getNormalPrice());
        goodsMapper.updateById(goods);
        // 冗余列同步：t_stock/t_seckill_activity 的 goods_name 是"配置行"语义（要反映当前名字），
        // 改名必须同事务刷过去；t_stock_operation 是操作时点流水，故意保持旧名不动
        stockMapper.update(null, new LambdaUpdateWrapper<Stock>()
                .set(Stock::getGoodsName, goods.getGoodsName())
                .eq(Stock::getGoodsId, id));
        activityMapper.update(null, new LambdaUpdateWrapper<SeckillActivity>()
                .set(SeckillActivity::getGoodsName, goods.getGoodsName())
                .eq(SeckillActivity::getGoodsId, id));
        // Cache Aside：更新 DB 后删除缓存，下次读时自然回填新数据。
        // 顺序是"先改库再删缓存"：即使删缓存失败，最坏情况是读到旧缓存，
        // 不会出现"删了缓存但库没改成功"的数据倒退。
        redis.delete(RedisKeys.goodsInfo(id));
        return goods;
    }

    @Override
    public GoodsDetailVO getDetail(Long id) {
        // 第一层：布隆说"一定不存在"→ 直接拒，连 Redis 都不查（穿透防线）
        if (!bloomFilter.mightContain(id)) {
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }

        String cacheKey = RedisKeys.goodsInfo(id);
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            if ("NULL".equals(cached)) {
                // 空值缓存：DB 里确认没有，短时间内不再查 DB
                throw new BizException(ErrorCode.GOODS_NOT_FOUND);
            }
            CachedGoodsVO vo = parseCached(cached);
            if (vo.getExpireAt().isAfter(LocalDateTime.now())) {
                return toDetail(vo, id);
            }
            // 逻辑已过期：SETNX 抢重建锁——抢到的人进 DB 重建，没抢到的人先返回旧值（击穿防线）
            Boolean gotLock = redis.opsForValue().setIfAbsent(
                    RedisKeys.rebuildLock(id), "1", Duration.ofSeconds(30));
            if (Boolean.TRUE.equals(gotLock)) {
                try {
                    rebuildCache(id);
                } finally {
                    redis.delete(RedisKeys.rebuildLock(id));
                }
            }
            return toDetail(vo, id);
        }

        // 缓存未命中：查 DB 回填（Cache Aside 的"旁路"回填）
        Goods goods = goodsMapper.selectById(id);
        if (goods == null) {
            redis.opsForValue().set(cacheKey, "NULL", EMPTY_TTL);
            throw new BizException(ErrorCode.GOODS_NOT_FOUND);
        }
        CachedGoodsVO vo = buildCached(goods, id);
        redis.opsForValue().set(cacheKey, toJson(vo), randomTtl());
        return toDetail(vo, id);
    }

    @Override
    public Page<Goods> page(int page, int size) {
        return goodsMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Goods>().orderByDesc(Goods::getCreateTime));
    }

    /** 查 DB 重建缓存（由抢到重建锁的线程调用）；商品已被删除则写空值缓存 */
    private void rebuildCache(Long id) {
        CachedGoodsVO vo = buildCached(goodsMapper.selectById(id), id);
        if (vo == null) {
            redis.opsForValue().set(RedisKeys.goodsInfo(id), "NULL", EMPTY_TTL);
            return;
        }
        redis.opsForValue().set(RedisKeys.goodsInfo(id), toJson(vo), randomTtl());
    }

    /** 组装缓存对象；商品不存在时返回 null，由调用方决定写空值缓存 */
    private CachedGoodsVO buildCached(Goods goods, Long id) {
        if (goods == null) {
            return null;
        }
        CachedGoodsVO vo = new CachedGoodsVO();
        vo.setId(goods.getId());
        vo.setGoodsName(goods.getGoodsName());
        vo.setDescription(goods.getDescription());
        vo.setNormalPrice(goods.getNormalPrice());
        vo.setStatus(goods.getStatus());
        vo.setCreateTime(goods.getCreateTime());
        // 逻辑过期时间 = 物理 TTL 的一半左右，保证"旧值兜底"窗口足够长
        vo.setExpireAt(LocalDateTime.now().plusMinutes(CACHE_TTL_MINUTES / 2L));
        return vo;
    }

    /** 缓存对象 + 实时库存 → 详情 VO（库存不进缓存，永远现查） */
    private GoodsDetailVO toDetail(CachedGoodsVO cached, Long id) {
        GoodsDetailVO vo = new GoodsDetailVO();
        vo.setId(cached.getId());
        vo.setGoodsName(cached.getGoodsName());
        vo.setDescription(cached.getDescription());
        vo.setNormalPrice(cached.getNormalPrice());
        vo.setStatus(cached.getStatus());
        vo.setCreateTime(cached.getCreateTime());
        Stock stock = stockMapper.selectOne(
                new LambdaQueryWrapper<Stock>().eq(Stock::getGoodsId, id));
        if (stock != null) {
            vo.setTotalStock(stock.getTotalStock());
            vo.setAvailableStock(stock.getAvailableStock());
            vo.setSoldCount(stock.getSoldCount());
        }
        return vo;
    }

    /** 雪崩防线：物理 TTL 在 30 分钟基础上加 0~5 分钟随机偏移 */
    private Duration randomTtl() {
        return Duration.ofMinutes(CACHE_TTL_MINUTES + ThreadLocalRandom.current().nextInt(5));
    }

    @SneakyThrows
    private String toJson(CachedGoodsVO vo) {
        return objectMapper.writeValueAsString(vo);
    }

    @SneakyThrows
    private CachedGoodsVO parseCached(String json) {
        JsonNode node = objectMapper.readTree(json);
        return objectMapper.treeToValue(node, CachedGoodsVO.class);
    }
}
