package com.seckill.goods.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.seckill.goods.dto.ActivityDTO;
import com.seckill.goods.entity.SeckillActivity;

public interface SeckillActivityService {

    SeckillActivity create(ActivityDTO dto);

    SeckillActivity getById(Long id);

    Page<SeckillActivity> page(int page, int size);
}
