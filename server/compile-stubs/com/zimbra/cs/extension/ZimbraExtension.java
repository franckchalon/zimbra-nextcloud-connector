package com.zimbra.cs.extension;

import com.zimbra.common.service.ServiceException;

public interface ZimbraExtension {
    String getName();
    void init() throws ServiceException;
    void destroy();
}
