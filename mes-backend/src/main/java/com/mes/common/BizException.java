package com.mes.common;

public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(2000, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
