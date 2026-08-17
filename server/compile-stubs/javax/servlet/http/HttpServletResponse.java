package javax.servlet.http;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletOutputStream;

public interface HttpServletResponse {
    void setStatus(int status);
    void setHeader(String name, String value);
    void setCharacterEncoding(String encoding);
    void setContentType(String contentType);
    PrintWriter getWriter() throws IOException;
    ServletOutputStream getOutputStream() throws IOException;
    boolean isCommitted();
}
