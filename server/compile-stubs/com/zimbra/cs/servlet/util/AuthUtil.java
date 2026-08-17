package com.zimbra.cs.servlet.util;

import com.zimbra.cs.account.AuthToken;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class AuthUtil {
    public static AuthToken getAuthTokenFromHttpReq(HttpServletRequest req, HttpServletResponse resp, boolean admin, boolean checkCsrf) {
        return new AuthToken();
    }
}
