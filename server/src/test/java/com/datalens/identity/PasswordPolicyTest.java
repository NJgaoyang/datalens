package com.datalens.identity;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PasswordPolicyTest {
 @Test void validatesBcryptByteBoundaryAndMinimum() {
    assertThatCode(()->PasswordPolicy.validate("x".repeat(72))).doesNotThrowAnyException();
    assertThatThrownBy(()->PasswordPolicy.validate("x".repeat(73))).isInstanceOf(com.datalens.common.web.DomainException.class);
    assertThatThrownBy(()->PasswordPolicy.validate("短".repeat(25))).isInstanceOf(com.datalens.common.web.DomainException.class);
    assertThatThrownBy(()->PasswordPolicy.validate("short")).isInstanceOf(com.datalens.common.web.DomainException.class);
 }
 @Test void productionCookieIsSecureHttpOnlyAndSameSite() {
    var request=new org.springframework.mock.web.MockHttpServletRequest();
    var response=new org.springframework.mock.web.MockHttpServletResponse();
    new SessionConfiguration().cookieSerializer(true).writeCookieValue(
        new org.springframework.session.web.http.CookieSerializer.CookieValue(request,response,"test-session"));
    assertThat(response.getHeader("Set-Cookie")).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/");
 }
 @Test void canonicalUsernameIsLocaleIndependent() {
    assertThat(IdentityService.username("Admin.ONE")).isEqualTo("admin.one");
    assertThatThrownBy(()->IdentityService.username("a b")).isInstanceOf(com.datalens.common.web.DomainException.class);
 }
 @Test void secretBearingInputsHaveRedactedStringRepresentations() {
    assertThat(new AuthController.LoginInput("admin","secret").toString()).doesNotContain("secret");
    assertThat(new UserAdminController.CreateInput("admin","secret","USER").toString()).doesNotContain("secret");
 }
}
