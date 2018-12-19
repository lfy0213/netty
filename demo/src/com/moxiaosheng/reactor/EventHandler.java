package com.moxiaosheng.reactor;

/**
 * @Desc
 * @Author zhuroufu
 * @Date 2018/12/19 15:27
 **/
public abstract class EventHandler {

    private InputSource source;

    public abstract void handle(Event event);

    public InputSource getSource() {
        return source;
    }

    public void setSource(InputSource source) {
        this.source = source;
    }


}
