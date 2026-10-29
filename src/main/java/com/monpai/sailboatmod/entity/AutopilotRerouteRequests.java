package com.monpai.sailboatmod.entity;

/**
 * 主线程上的自救互斥位与请求身份。停止/重新发车时作废旧身份,迟到的成功或失败回调都不能落地,
 * 也不能释放新请求持有的互斥位。后台线程只捕获身份,不访问本状态。
 */
final class AutopilotRerouteRequests {
    static final class Request {
        private Request() {
        }
    }

    private Request active;

    boolean isInFlight() {
        return active != null;
    }

    Request claim() {
        if (active != null) {
            return null;
        }
        active = new Request();
        return active;
    }

    /** 只有当前请求可完成;false 表示回调过时,调用者必须跳过结果与失败处理。 */
    boolean complete(Request request) {
        if (request == null || active != request) {
            return false;
        }
        active = null;
        return true;
    }

    void invalidate() {
        active = null;
    }
}
