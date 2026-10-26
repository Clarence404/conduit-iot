package dev.conduit.server.organization;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** 组织模型接口的请求与响应。 */
public final class OrganizationDtos {
    private OrganizationDtos() {
    }

    public static final String CODE = "^[a-z][a-z0-9-]{1,31}$";
    public static final String STATUS = "ACTIVE|DISABLED";

    public record SaveCustomerRequest(@NotBlank @Pattern(regexp = CODE) String code,
                                      @NotBlank @Size(max = 128) String name,
                                      @NotBlank @Pattern(regexp = STATUS) String status) {
    }

    public record CustomerView(String code, String name, String status, OffsetDateTime createdAt) {
        static CustomerView of(Customer value) {
            return new CustomerView(value.getCode(), value.getName(), value.getStatus(), value.getCreatedAt());
        }
    }

    public record SaveProjectRequest(@NotBlank @Pattern(regexp = CODE) String code,
                                     @NotBlank @Size(max = 128) String name,
                                     @Pattern(regexp = CODE) String customerCode,
                                     @NotBlank @Pattern(regexp = STATUS) String status) {
    }

    public record ProjectView(String code, String name, String customerCode, String status, boolean systemDefault,
                              OffsetDateTime createdAt) {
    }

    public record SaveSiteRequest(@NotBlank @Pattern(regexp = CODE) String code,
                                  @NotBlank @Size(max = 128) String name,
                                  @NotBlank @Pattern(regexp = CODE) String projectCode,
                                  @NotBlank @Size(max = 64) String timezone,
                                  @NotBlank @Pattern(regexp = STATUS) String status) {
    }

    public record SiteView(String code, String name, String projectCode, String timezone, String status,
                           boolean systemDefault, OffsetDateTime createdAt) {
    }

    public record SaveAssetRequest(@NotBlank @Pattern(regexp = CODE) String code,
                                   @NotBlank @Size(max = 128) String name,
                                   @NotBlank @Pattern(regexp = CODE) String siteCode,
                                   @Pattern(regexp = CODE) String parentCode,
                                   @Size(max = 64) String type,
                                   @NotBlank @Pattern(regexp = STATUS) String status) {
    }

    public record AssetView(String code, String name, String siteCode, String parentCode, String type, String status,
                            OffsetDateTime createdAt) {
    }
}
