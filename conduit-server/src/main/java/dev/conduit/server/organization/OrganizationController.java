package dev.conduit.server.organization;

import dev.conduit.server.organization.OrganizationDtos.AssetView;
import dev.conduit.server.organization.OrganizationDtos.CustomerView;
import dev.conduit.server.organization.OrganizationDtos.ProjectView;
import dev.conduit.server.organization.OrganizationDtos.SaveAssetRequest;
import dev.conduit.server.organization.OrganizationDtos.SaveCustomerRequest;
import dev.conduit.server.organization.OrganizationDtos.SaveProjectRequest;
import dev.conduit.server.organization.OrganizationDtos.SaveSiteRequest;
import dev.conduit.server.organization.OrganizationDtos.SiteView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 客户、项目、站点和资产 REST API；B2 会在服务层增加作用域权限。 */
@RestController
@RequestMapping("/api/v1")
public class OrganizationController {
    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @GetMapping("/customers") public List<CustomerView> customers() { return service.customers(); }
    @PostMapping("/customers") @ResponseStatus(HttpStatus.CREATED)
    public CustomerView createCustomer(@Valid @RequestBody SaveCustomerRequest request) { return service.createCustomer(request); }
    @PutMapping("/customers/{code}")
    public CustomerView updateCustomer(@PathVariable String code, @Valid @RequestBody SaveCustomerRequest request) { return service.updateCustomer(code, request); }
    @DeleteMapping("/customers/{code}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCustomer(@PathVariable String code) { service.deleteCustomer(code); }

    @GetMapping("/projects") public List<ProjectView> projects() { return service.projects(); }
    @PostMapping("/projects") @ResponseStatus(HttpStatus.CREATED)
    public ProjectView createProject(@Valid @RequestBody SaveProjectRequest request) { return service.createProject(request); }
    @PutMapping("/projects/{code}")
    public ProjectView updateProject(@PathVariable String code, @Valid @RequestBody SaveProjectRequest request) { return service.updateProject(code, request); }
    @DeleteMapping("/projects/{code}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable String code) { service.deleteProject(code); }

    @GetMapping("/sites") public List<SiteView> sites() { return service.sites(); }
    @PostMapping("/sites") @ResponseStatus(HttpStatus.CREATED)
    public SiteView createSite(@Valid @RequestBody SaveSiteRequest request) { return service.createSite(request); }
    @PutMapping("/sites/{code}")
    public SiteView updateSite(@PathVariable String code, @Valid @RequestBody SaveSiteRequest request) { return service.updateSite(code, request); }
    @DeleteMapping("/sites/{code}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSite(@PathVariable String code) { service.deleteSite(code); }

    @GetMapping("/assets") public List<AssetView> assets() { return service.assets(); }
    @PostMapping("/assets") @ResponseStatus(HttpStatus.CREATED)
    public AssetView createAsset(@Valid @RequestBody SaveAssetRequest request) { return service.createAsset(request); }
    @PutMapping("/assets/{code}")
    public AssetView updateAsset(@PathVariable String code, @Valid @RequestBody SaveAssetRequest request) { return service.updateAsset(code, request); }
    @DeleteMapping("/assets/{code}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAsset(@PathVariable String code) { service.deleteAsset(code); }
}
