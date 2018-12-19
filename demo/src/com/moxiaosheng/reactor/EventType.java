package com.moxiaosheng.reactor;

/**
 * @Desc
 * @Author zhuroufu
 * @Date 2018/12/19 15:26
 **/
public enum EventType {
    /**
     * 建立连接
     */
    ACCEPT,
    /**
     * 可读
     */
    READ,
    /**
     * 可写
     */
    WRITE;
}
