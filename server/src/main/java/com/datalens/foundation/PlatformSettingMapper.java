package com.datalens.foundation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlatformSettingMapper {
    @Select("SELECT setting_value FROM sys_platform_setting WHERE setting_key = 'product.name'")
    String productName();
}
