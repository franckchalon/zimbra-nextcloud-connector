package com.zimbra.common.service;

public class ServiceException extends Exception {
    public ServiceException(String message, Throwable cause) { super(message, cause); }
    public static ServiceException FAILURE(String message, Throwable cause) { return new ServiceException(message, cause); }
}
