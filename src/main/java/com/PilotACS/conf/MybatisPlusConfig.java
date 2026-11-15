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

/**
 * MyBatis-Plus 插件：动态表名（OLT/ONU 分组表域）+ 分页。
 * 动态表名拦截器必须放在分页拦截器之前，分页生成的 count 语句才能拿到同一张物理表。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // OLT/光猫分组各一套物理表：res_group / res_group_member / res_acl 按 ThreadLocal 域映射
        DynamicTableNameInnerInterceptor dynamic = new DynamicTableNameInnerInterceptor();
        dynamic.setTableNameHandler((sql, tableName) -> GroupScopeContext.mapTable(tableName));
        interceptor.addInnerInterceptor(dynamic);
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
