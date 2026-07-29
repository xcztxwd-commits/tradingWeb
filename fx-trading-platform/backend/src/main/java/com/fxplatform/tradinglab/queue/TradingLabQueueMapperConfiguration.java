package com.fxplatform.tradinglab.queue;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan(
    basePackageClasses = TradingLabQueueRepository.class,
    annotationClass = Mapper.class)
class TradingLabQueueMapperConfiguration {
}
