package com.seckill.goods.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.goods.dto.GoodsDTO;
import com.seckill.goods.entity.Goods;
import com.seckill.goods.vo.GoodsDetailVO;

public interface GoodsService {

    Goods create(GoodsDTO dto);

    Goods update(Long id, GoodsDTO dto);

    GoodsDetailVO getDetail(Long id);

    Page<Goods> page(int page, int size);
}
