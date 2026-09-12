package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.seckill.common.api.goods.DeductStockRequest;
import com.seckill.common.api.goods.RollbackStockRequest;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.entity.StockOperation;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.goods.mapper.StockOperationMapper;
import com.seckill.goods.service.StockOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存扣减/回滚实现（跨服务补偿的幂等核心）。
 *
 * 扣减顺序设计（M5 拆分后 DbOrderWriter 的本地事务拆散，由调用方约定）：
 * 先扣库存（本服务事务：条件更新 + 流水同事务），再插订单——库存是稀缺资源先占住，
 * 插单失败只需调用回滚即可；反过来（先插单）失败时要取消订单，状态机复杂得多。
 *
 * 幂等保障（requestId 唯一索引）：
 * - Feign 超时后 seckill 侧重试，同 requestId 二次调用直接返回成功，不会重复扣减；
 * - 并发撞唯一索引时，事务整体回滚（库存更新一并撤销），按已处理返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockOperationServiceImpl implements StockOperationService {

    private final StockMapper stockMapper;
    private final StockOperationMapper operationMapper;

    @Override
    @Transactional
    public void deduct(DeductStockRequest request) {
        // 1. 幂等快路径：同一 requestId 已扣过 → 直接返回成功（Feign 超时重试场景）
        Long count = operationMapper.selectCount(new LambdaQueryWrapper<StockOperation>()
                .eq(StockOperation::getRequestId, request.getRequestId()));
        if (count > 0) {
            log.info("库存扣减幂等命中: requestId={}", request.getRequestId());
            return;
        }

        // 2. 条件更新扣库存：available_stock > 0 是防超卖的核心条件（拆分前 DbOrderWriter 同款）
        int rows = stockMapper.update(null, new LambdaUpdateWrapper<Stock>()
                .setSql("available_stock = available_stock - 1")
                .setSql("sold_count = sold_count + 1")
                .setSql("version = version + 1")
                .eq(Stock::getGoodsId, request.getGoodsId())
                .gt(Stock::getAvailableStock, 0));
        if (rows == 0) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }

        // 3. 流水与扣减同事务：requestId 唯一键兜底并发重复
        //    （DuplicateKey 时事务整体回滚，库存更新一并撤销，相当于"没扣过"，调用方重试即可）
        StockOperation op = new StockOperation();
        op.setRequestId(request.getRequestId());
        op.setGoodsId(request.getGoodsId());
        op.setGoodsName(request.getGoodsName() == null ? "" : request.getGoodsName());
        op.setActivityId(request.getActivityId());
        op.setActivityName(request.getActivityName() == null ? "" : request.getActivityName());
        op.setAmount(request.getAmount() == null ? 1 : request.getAmount());
        op.setStatus(0);
        try {
            operationMapper.insert(op);
        } catch (DuplicateKeyException e) {
            log.warn("库存扣减并发幂等命中: requestId={}", request.getRequestId());
        }
    }

    @Override
    @Transactional
    public void rollback(RollbackStockRequest request) {
        // 1. 无流水（从未扣过）或已回滚 → 幂等返回（对账任务可无条件调用）
        StockOperation op = operationMapper.selectOne(new LambdaQueryWrapper<StockOperation>()
                .eq(StockOperation::getRequestId, request.getRequestId()));
        if (op == null || op.getStatus() == 1) {
            return;
        }

        // 2. 条件推进流水状态：只有 0→1 成功的那次才真正还库存，并发重复回滚在此短路
        int rows = operationMapper.update(null, new LambdaUpdateWrapper<StockOperation>()
                .set(StockOperation::getStatus, 1)
                .eq(StockOperation::getRequestId, request.getRequestId())
                .eq(StockOperation::getStatus, 0));
        if (rows == 0) {
            return;
        }

        // 3. 与流水推进同事务还库存
        stockMapper.update(null, new LambdaUpdateWrapper<Stock>()
                .setSql("available_stock = available_stock + 1")
                .setSql("sold_count = sold_count - 1")
                .eq(Stock::getGoodsId, op.getGoodsId()));
        log.info("库存回滚完成: requestId={}, goodsId={}", request.getRequestId(), op.getGoodsId());
    }
}
