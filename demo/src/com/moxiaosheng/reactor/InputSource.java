package com.moxiaosheng.reactor;

/**
 * @Desc reactor模式中处理的原始输入对象
 * @Author zhuroufu
 * @Date 2018/12/19 15:23
 **/
public class InputSource {

    private Object data;
    private long id;

    public InputSource(Object data, long id) {
        this.data = data;
        this.id = id;
    }

    @Override
    public String toString() {
        return "InputSource{" +
                "data=" + data +
                ", id=" + id +
                '}';
    }

}
