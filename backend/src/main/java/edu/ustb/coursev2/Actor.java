package edu.ustb.coursev2;

import java.util.*;
import org.springframework.security.core.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

public record Actor(
    String id,
    String username,
    String password,
    String displayName,
    String role,
    String studentId,
    String teacherId,
    boolean enabled,
    long accountVersion)
    implements UserDetails {
  public Actor(
      String id,
      String username,
      String password,
      String displayName,
      String role,
      String studentId,
      String teacherId,
      boolean enabled) {
    this(id, username, password, displayName, role, studentId, teacherId, enabled, 0);
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  public Map<String, Object> view() {
    return Api.object(
        "id",
        id,
        "username",
        username,
        "displayName",
        displayName,
        "role",
        role,
        "studentId",
        studentId,
        "teacherId",
        teacherId);
  }

  public static Actor current() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Actor a))
      throw new Api.Failure(401, "UNAUTHENTICATED", "请先登录");
    return a;
  }

  public static void require(String... roles) {
    Actor a = current();
    if (Arrays.stream(roles).noneMatch(a.role::equals))
      throw new Api.Failure(403, "FORBIDDEN", "当前身份无权执行此操作");
  }
}
