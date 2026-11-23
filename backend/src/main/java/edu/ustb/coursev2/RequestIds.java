package edu.ustb.coursev2;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class RequestIds extends OncePerRequestFilter {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  public static String current() {
    String value = CURRENT.get();
    return value == null ? "background-" + Db.uid() : value;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = Db.uid();
    CURRENT.set(id);
    request.setAttribute("requestId", id);
    response.setHeader("X-Request-ID", id);
    try {
      chain.doFilter(request, response);
    } finally {
      CURRENT.remove();
    }
  }
}
