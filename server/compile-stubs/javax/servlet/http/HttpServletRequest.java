package javax.servlet.http;

import java.io.IOException;
import javax.servlet.ServletInputStream;

public interface HttpServletRequest {
    String getRequestURI();
    String getParameter(String name);
    String getHeader(String name);
    String getContentType();
    long getContentLengthLong();
    ServletInputStream getInputStream() throws IOException;
}
