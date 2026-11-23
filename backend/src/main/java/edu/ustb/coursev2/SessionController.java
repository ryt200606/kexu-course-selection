package edu.ustb.coursev2;

import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.*;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/auth")
public class SessionController {
  private final AuthenticationManager auth;
  private final SecurityContextRepository contexts;
  private final CsrfTokenRepository tokens;

  public SessionController(
      AuthenticationManager auth, SecurityContextRepository contexts, CsrfTokenRepository tokens) {
    this.auth = auth;
    this.contexts = contexts;
    this.tokens = tokens;
  }

  @GetMapping("/csrf")
  public Map<String, Object> csrf(HttpServletRequest request) {
    CsrfToken t = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    return Api.object("token", t.getToken(), "headerName", t.getHeaderName());
  }

  @PostMapping("/login")
  public Map<String, Object> login(
      @RequestBody Map<String, Object> body,
      HttpServletRequest request,
      HttpServletResponse response) {
    String username = Api.text(body, "username", 80);
    Object supplied = body.get("password");
    if (!(supplied instanceof String password) || password.isEmpty() || password.length() > 200)
      throw Api.invalid("密码不能为空或超长");
    try {
      Authentication authenticated =
          auth.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(username, password));
      if (request.getSession(false) != null) request.changeSessionId();
      else request.getSession(true);
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authenticated);
      SecurityContextHolder.setContext(context);
      contexts.saveContext(context, request, response);
      tokens.saveToken(null, request, response);
      return ((Actor) authenticated.getPrincipal()).view();
    } catch (AuthenticationException e) {
      throw new Api.Failure(401, "BAD_CREDENTIALS", "用户名或密码不正确，或账号已停用");
    }
  }

  @GetMapping("/me")
  public Map<String, Object> me() {
    return Actor.current().view();
  }

  @PostMapping("/logout")
  public Map<String, Object> logout(HttpServletRequest q, HttpServletResponse s) {
    tokens.saveToken(null, q, s);
    SecurityContextHolder.clearContext();
    HttpSession session = q.getSession(false);
    if (session != null) session.invalidate();
    return Map.of("status", "OK");
  }
}
