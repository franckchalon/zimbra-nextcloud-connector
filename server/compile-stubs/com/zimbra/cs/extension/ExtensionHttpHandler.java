package com.zimbra.cs.extension;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class ExtensionHttpHandler {
    public abstract String getPath();
    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {}
    public void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {}
}
