package edu.ustb.coursev2;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Disable/password reset takes effect for existing sessions, not only the next login. */
final class SessionValidityFilter extends OncePerRequestFilter {
  private final Db db;
  private final ObjectMapper json;

  SessionValidityFilter(Db db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Actor actor) {
      try {
        List<Map<String, Object>> current =
            db.rows("SELECT enabled,version FROM cs2_user WHERE id=?", actor.id());
        if (current.isEmpty()
            || Db.num(current.get(0), "enabled") != 1
            || Db.lng(current.get(0), "version") != actor.accountVersion()) {
          SecurityContextHolder.clearContext();
          if (request.getSession(false) != null) request.getSession(false).invalidate();
          response.setStatus(401);
          response.setContentType("application/json;charset=UTF-8");
          json.writeValue(
              response.getOutputStream(), Errors.body("ACCOUNT_CHANGED", "账号状态已变化，请重新登录"));
          return;
        }
      } catch (org.springframework.dao.DataAccessException error) {
        response.setStatus(503);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(
            response.getOutputStream(), Errors.body("DATABASE_UNAVAILABLE", "暂时无法校验账号状态"));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
