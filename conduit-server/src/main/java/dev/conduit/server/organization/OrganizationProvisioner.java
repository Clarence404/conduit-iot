package dev.conduit.server.organization;

import dev.conduit.server.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 为新租户创建兼容旧设备 API 的默认项目和默认站点。 */
@Service
public class OrganizationProvisioner {
    private final ProjectMapper projects;
    private final SiteMapper sites;

    public OrganizationProvisioner(ProjectMapper projects, SiteMapper sites) {
        this.projects = projects;
        this.sites = sites;
    }

    public void provision(long tenantId) {
        TenantContext.runAs(tenantId, () -> {
            Project project = new Project();
            project.setCode("default");
            project.setName("默认项目");
            project.setStatus("ACTIVE");
            project.setIsDefault(true);
            projects.insert(project);

            Site site = new Site();
            site.setProjectId(project.getId());
            site.setCode("default");
            site.setName("默认站点");
            site.setTimezone("Asia/Shanghai");
            site.setStatus("ACTIVE");
            site.setIsDefault(true);
            sites.insert(site);
        });
    }
}
