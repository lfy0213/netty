package com.moxiaosheng.reactor;

/**
 * @Desc
 * @Author zhuroufu
 * @Date 2018/12/19 15:29
 **/
public class Server {

    Selector selector = new Selector();
    Dispatcher eventLooper = new Dispatcher(selector);
    Acceptor acceptor;

    Server(int port) {
        acceptor = new Acceptor(selector, port);
    }

    public void start() {
        eventLooper.registEventHandler(EventType.ACCEPT, new AcceptEventHandler(selector));
        new Thread(acceptor, "Acceptor-" + acceptor.getPort()).start();
        eventLooper.handleEvents();
    }


    public static void main(String[] args) {
        Server server = new Server(8888);
        server.start();
    }
}
