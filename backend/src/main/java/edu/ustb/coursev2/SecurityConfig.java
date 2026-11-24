package edu.ustb.coursev2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.*;
import org.springframework.security.web.context.*;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(11);
  }

  @Bean
  UserDetailsService users(Db db) {
    return username ->
        db
            .jdbc
            .query(
                "SELECT"
                    + " id,username,password_hash,display_name,role,student_id,teacher_id,enabled,version"
                    + " FROM cs2_user WHERE username=?",
                (r, n) ->
                    new Actor(
                        r.getString(1),
                        r.getString(2),
                        r.getString(3),
                        r.getString(4),
                        r.getString(5),
                        r.getString(6),
                        r.getString(7),
                        r.getInt(8) == 1,
                        r.getLong(9)),
                username)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
  }

  @Bean
  AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
    DaoAuthenticationProvider p = new DaoAuthenticationProvider(users);
    p.setPasswordEncoder(encoder);
    return new ProviderManager(p);
  }

  @Bean
  SecurityContextRepository contexts() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfTokens() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
      type =
          org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
  SecurityFilterChain security(
      HttpSecurity http,
      SecurityContextRepository contexts,
      CsrfTokenRepository tokens,
      ObjectMapper json,
      Db db)
      throws Exception {
    http.securityContext(c -> c.securityContextRepository(contexts).requireExplicitSave(true));
    http.csrf(
        c ->
            c.csrfTokenRepository(tokens)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()));
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers("/api/v2/auth/csrf", "/api/v2/auth/login", "/api/v2/health", "/error")
                .permitAll()
                .requestMatchers("/api/v2/admin/**", "/api/v3/admin/**")
                .hasRole("ADMIN")
                .requestMatchers("/api/v2/student/**", "/api/v3/student/**")
                .hasRole("STUDENT")
                .requestMatchers("/api/v2/teacher/**")
                .hasAnyRole("TEACHER", "ADMIN")
                .requestMatchers("/api/v3/teacher/**")
                .hasRole("TEACHER")
                .anyRequest()
                .authenticated());
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint(
                    (q, s, x) -> {
                      s.setStatus(401);
                      s.setContentType("application/json;charset=UTF-8");
                      json.writeValue(s.getOutputStream(), Errors.body("UNAUTHENTICATED", "请先登录"));
                    })
                .accessDeniedHandler(
                    (q, s, x) -> {
                      s.setStatus(403);
                      s.setContentType("application/json;charset=UTF-8");
                      json.writeValue(
                          s.getOutputStream(),
                          Errors.body(
                              x instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                              x instanceof CsrfException ? "请求校验已过期，请刷新后重试" : "当前身份无权执行此操作"));
                    }));
    http.logout(l -> l.disable()).formLogin(l -> l.disable()).httpBasic(l -> l.disable());
    http.addFilterAfter(
        new SessionValidityFilter(db, json),
        org.springframework.security.web.context.SecurityContextHolderFilter.class);
    return http.build();
  }
}
