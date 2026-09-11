package com.seckill.goods.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.seckill.common.api.goods.DeductStockRequest;
import com.seckill.common.api.goods.RollbackStockRequest;
import com.seckill.common.exception.BizException;
import com.seckill.common.result.ErrorCode;
import com.seckill.goods.entity.Stock;
import com.seckill.goods.entity.StockOperation;
import com.seckill.goods.mapper.StockMapper;
import com.seckill.goods.mapper.StockOperationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存扣减/回滚幂等测试（M5 拆分核心正确性：跨服务补偿不允许重复生效）。
 */
@ExtendWith(MockitoExtension.class)
class StockOperationServiceImplTest {

    /**
     * 纯 Mockito 环境没有 Spring 启动流程，MyBatis-Plus 的 lambda 列缓存不会被初始化，
     * LambdaUpdateWrapper.set(实体::字段) 会抛"can not find lambda cache"。
     * 这里手动初始化两个实体的 TableInfo（与 Spring 启动时 Mapper 注册做的事等价）。
     */
    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StockOperation.class);
        TableInfoHelper.initTableInfo(assistant, Stock.class);
    }

    @Mock
    private StockMapper stockMapper;
    @Mock
    private StockOperationMapper operationMapper;

    @InjectMocks
    private StockOperationServiceImpl service;

    private DeductStockRequest deductReq(String requestId) {
        return new DeductStockRequest(requestId, 1L, 2L, 1);
    }

    @Test
    @DisplayName("正常扣减：库存条件更新成功 + 写入流水")
    void deductSuccess() {
        when(operationMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(stockMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service.deduct(deductReq("r1"));

        verify(operationMapper).insert(any(StockOperation.class));
    }

    @Test
    @DisplayName("幂等扣减：同 requestId 已存在流水，直接返回不重复扣")
    void deductIdempotent() {
        when(operationMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        service.deduct(deductReq("r1"));

        // 关键断言：不再动库存表
        verify(stockMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("库存耗尽：条件更新影响 0 行抛库存不足")
    void deductStockNotEnough() {
        when(operationMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(stockMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        BizException e = assertThrows(BizException.class, () -> service.deduct(deductReq("r1")));
        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), e.getCode());
        verify(operationMapper, never()).insert(any(StockOperation.class));
    }

    @Test
    @DisplayName("正常回滚：流水 0→1 推进成功后还库存")
    void rollbackSuccess() {
        StockOperation op = new StockOperation();
        op.setRequestId("r1");
        op.setGoodsId(1L);
        op.setStatus(0);
        when(operationMapper.selectOne(any(Wrapper.class))).thenReturn(op);
        when(operationMapper.update(any(), any(Wrapper.class))).thenReturn(1);

        service.rollback(new RollbackStockRequest("r1"));

        verify(stockMapper).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("幂等回滚：无流水或已回滚，直接返回")
    void rollbackIdempotent() {
        when(operationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertDoesNotThrow(() -> service.rollback(new RollbackStockRequest("r1")));
        verify(operationMapper, never()).update(any(), any(Wrapper.class));

        StockOperation op = new StockOperation();
        op.setRequestId("r2");
        op.setStatus(1);
        when(operationMapper.selectOne(any(Wrapper.class))).thenReturn(op);
        assertDoesNotThrow(() -> service.rollback(new RollbackStockRequest("r2")));
        // 已回滚的流水不再推进，也不再还库存
        verify(operationMapper, never()).update(any(), any(Wrapper.class));
        verify(stockMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("并发回滚：状态推进竞争失败的一方短路，库存只还一次")
    void rollbackConcurrentShortCircuit() {
        StockOperation op = new StockOperation();
        op.setRequestId("r1");
        op.setGoodsId(1L);
        op.setStatus(0);
        when(operationMapper.selectOne(any(Wrapper.class))).thenReturn(op);
        // 条件推进影响 0 行 = 另一个线程已经回滚过了
        when(operationMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        service.rollback(new RollbackStockRequest("r1"));

        verify(stockMapper, never()).update(any(), any(Wrapper.class));
    }
}
