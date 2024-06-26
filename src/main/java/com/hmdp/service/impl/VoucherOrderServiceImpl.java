package com.hmdp.service.impl;

import com.hmdp.controller.VoucherController;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService
{

    @Resource
    private ISeckillVoucherService seckillVoucherService ;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate ;
    @Override
    public Result seckillVoucher(Long voucherId)
    {
        // 1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now()))
        {
            return Result.fail("秒杀尚未开始!") ;
        }

        // 3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now()))
        {
            return Result.fail("秒杀已经结束！") ;
        }

        // 4.判断库存是否充足
        if (voucher.getStock() < 1)
        {
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();

        //创建锁对象 (给指针)
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 获取锁
        boolean islock = lock.tryLock(1000);
        // 判断是否成功
        if (!islock)
        {
            return Result.fail("一个用户只允许下一单") ;
        }

        try
        {
            // 获取代理对象
            IVoucherService proxy = (IVoucherService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
        finally
        {
            lock.unlcok();
        }
    }

    @Transactional
    public createVoucherOrder(Long voucherId)
    {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

            // 查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();

            if (count > 0)
            {
                return Result.fail("用户已购买过");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucher).gt("stock", voucher.getStock()) // where id = ? and stock = ?
                    .update();

            if (!success)
            {
                return Result.fail("库存不足");
            }

            // 7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1 订单id
            long orderId = RedisIdWorker.nextId("order");
            voucherOrder.setVoucherId(orderId);

            // 7.2 用户id
            voucherOrder.setUserId(userId);

            // 7.3 代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        }
}
