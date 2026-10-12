package com.tr069.acs.common;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页响应体，对应管理端的列表查询。
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页数据
     */
    private List<T> items;

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页码，从 1 开始
     */
    private long page;

    /**
     * 每页条数
     */
    private long size;

    public PageResult() {
    }

    public PageResult(List<T> items, long total, long page, long size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> items, long total, long page, long size) {
        return new PageResult<>(items, total, page, size);
    }
}
