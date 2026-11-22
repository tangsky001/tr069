/**
 * @author: tangsky001
 * @date:   2026年11月15日 上午3:04:04.370
 *
 */

package com.PilotACS.conf;

import com.PilotACS.group.config.GroupScopeContext;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MyBatis-Plus 插件：动态表名 + 分页 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        DynamicTableNameInnerInterceptor dynamic = new DynamicTableNameInnerInterceptor();
        dynamic.setTableNameHandler((sql, tableName) -> GroupScopeContext.mapTable(tableName));
        interceptor.addInnerInterceptor(dynamic);
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
