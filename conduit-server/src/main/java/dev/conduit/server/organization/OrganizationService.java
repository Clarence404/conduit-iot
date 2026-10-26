package dev.conduit.server.organization;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.conduit.server.device.Device;
import dev.conduit.server.device.DeviceMapper;
import dev.conduit.server.organization.OrganizationDtos.AssetView;
import dev.conduit.server.organization.OrganizationDtos.CustomerView;
import dev.conduit.server.organization.OrganizationDtos.ProjectView;
import dev.conduit.server.organization.OrganizationDtos.SaveAssetRequest;
import dev.conduit.server.organization.OrganizationDtos.SaveCustomerRequest;
import dev.conduit.server.organization.OrganizationDtos.SaveProjectRequest;
import dev.conduit.server.organization.OrganizationDtos.SaveSiteRequest;
import dev.conduit.server.organization.OrganizationDtos.SiteView;
import dev.conduit.server.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** V0.3-B1 组织骨架的增删改查与层级约束。 */
@Service
public class OrganizationService {
    private static final int MAX_ASSET_DEPTH = 8;

    private final CustomerMapper customers;
    private final ProjectMapper projects;
    private final SiteMapper sites;
    private final AssetMapper assets;
    private final DeviceMapper devices;
    private final Clock clock;

    public OrganizationService(CustomerMapper customers, ProjectMapper projects, SiteMapper sites,
                               AssetMapper assets, DeviceMapper devices, Clock clock) {
        this.customers = customers;
        this.projects = projects;
        this.sites = sites;
        this.assets = assets;
        this.devices = devices;
        this.clock = clock;
    }

    public List<CustomerView> customers() {
        return customers.selectList(new LambdaQueryWrapper<Customer>().orderByAsc(Customer::getName))
                .stream().map(CustomerView::of).toList();
    }

    public CustomerView createCustomer(SaveCustomerRequest request) {
        ensureCustomerCodeAvailable(request.code(), null);
        Customer value = new Customer();
        value.setCode(request.code());
        value.setName(request.name().trim());
        value.setStatus(request.status());
        customers.insert(value);
        return CustomerView.of(customers.selectById(value.getId()));
    }

    public CustomerView updateCustomer(String code, SaveCustomerRequest request) {
        requireSameCode(code, request.code());
        Customer value = requireCustomer(code);
        value.setName(request.name().trim());
        value.setStatus(request.status());
        value.setUpdatedAt(now());
        customers.updateById(value);
        return CustomerView.of(value);
    }

    public void deleteCustomer(String code) {
        Customer value = requireCustomer(code);
        if (projects.exists(new LambdaQueryWrapper<Project>().eq(Project::getCustomerId, value.getId()))) {
            throw ApiException.conflict("客户下仍有项目，不能删除");
        }
        customers.deleteById(value.getId());
    }

    public List<ProjectView> projects() {
        List<Project> rows = projects.selectList(new LambdaQueryWrapper<Project>().orderByAsc(Project::getName));
        List<Long> customerIds = rows.stream().map(Project::getCustomerId).filter(id -> id != null).distinct().toList();
        Map<Long, String> customerCodes = customerIds.isEmpty()
                ? new HashMap<>() : codes(customers.selectBatchIds(customerIds));
        return rows.stream().map(p -> new ProjectView(p.getCode(), p.getName(), customerCodes.get(p.getCustomerId()),
                p.getStatus(), Boolean.TRUE.equals(p.getIsDefault()), p.getCreatedAt())).toList();
    }

    public ProjectView createProject(SaveProjectRequest request) {
        ensureProjectCodeAvailable(request.code(), null);
        Customer customer = optionalCustomer(request.customerCode());
        Project value = new Project();
        value.setCustomerId(customer == null ? null : customer.getId());
        value.setCode(request.code());
        value.setName(request.name().trim());
        value.setStatus(request.status());
        value.setIsDefault(false);
        projects.insert(value);
        return projectView(projects.selectById(value.getId()), customer);
    }

    public ProjectView updateProject(String code, SaveProjectRequest request) {
        requireSameCode(code, request.code());
        Project value = requireProject(code);
        Customer customer = optionalCustomer(request.customerCode());
        value.setCustomerId(customer == null ? null : customer.getId());
        value.setName(request.name().trim());
        value.setStatus(request.status());
        value.setUpdatedAt(now());
        projects.updateById(value);
        return projectView(value, customer);
    }

    public void deleteProject(String code) {
        Project value = requireProject(code);
        if (Boolean.TRUE.equals(value.getIsDefault())) {
            throw ApiException.conflict("默认项目不能删除");
        }
        if (sites.exists(new LambdaQueryWrapper<Site>().eq(Site::getProjectId, value.getId()))) {
            throw ApiException.conflict("项目下仍有站点，不能删除");
        }
        projects.deleteById(value.getId());
    }

    public List<SiteView> sites() {
        List<Site> rows = sites.selectList(new LambdaQueryWrapper<Site>().orderByAsc(Site::getName));
        Map<Long, String> projectCodes = projectCodes(rows.stream().map(Site::getProjectId).distinct().toList());
        return rows.stream().map(s -> siteView(s, projectCodes.get(s.getProjectId()))).toList();
    }

    public SiteView createSite(SaveSiteRequest request) {
        ensureSiteCodeAvailable(request.code(), null);
        Project project = requireProject(request.projectCode());
        requireTimezone(request.timezone());
        Site value = new Site();
        value.setProjectId(project.getId());
        value.setCode(request.code());
        value.setName(request.name().trim());
        value.setTimezone(request.timezone());
        value.setStatus(request.status());
        value.setIsDefault(false);
        sites.insert(value);
        return siteView(sites.selectById(value.getId()), project.getCode());
    }

    public SiteView updateSite(String code, SaveSiteRequest request) {
        requireSameCode(code, request.code());
        Site value = requireSite(code);
        Project project = requireProject(request.projectCode());
        if (!project.getId().equals(value.getProjectId())
                && (assets.exists(new LambdaQueryWrapper<Asset>().eq(Asset::getSiteId, value.getId()))
                || devices.exists(new LambdaQueryWrapper<Device>().eq(Device::getSiteId, value.getId())))) {
            throw ApiException.conflict("站点下仍有资产或设备，不能移动到其他项目");
        }
        requireTimezone(request.timezone());
        value.setProjectId(project.getId());
        value.setName(request.name().trim());
        value.setTimezone(request.timezone());
        value.setStatus(request.status());
        value.setUpdatedAt(now());
        sites.updateById(value);
        return siteView(value, project.getCode());
    }

    public void deleteSite(String code) {
        Site value = requireSite(code);
        if (Boolean.TRUE.equals(value.getIsDefault())) {
            throw ApiException.conflict("默认站点不能删除");
        }
        if (assets.exists(new LambdaQueryWrapper<Asset>().eq(Asset::getSiteId, value.getId()))
                || devices.exists(new LambdaQueryWrapper<Device>().eq(Device::getSiteId, value.getId()))) {
            throw ApiException.conflict("站点下仍有资产或设备，不能删除");
        }
        sites.deleteById(value.getId());
    }

    public List<AssetView> assets() {
        List<Asset> rows = assets.selectList(new LambdaQueryWrapper<Asset>().orderByAsc(Asset::getName));
        Map<Long, String> siteCodes = siteCodes(rows.stream().map(Asset::getSiteId).distinct().toList());
        Map<Long, String> assetCodes = new HashMap<>();
        rows.forEach(a -> assetCodes.put(a.getId(), a.getCode()));
        return rows.stream().map(a -> assetView(a, siteCodes.get(a.getSiteId()), assetCodes.get(a.getParentId()))).toList();
    }

    @Transactional
    public AssetView createAsset(SaveAssetRequest request) {
        ensureAssetCodeAvailable(request.code(), null);
        Site site = requireSite(request.siteCode());
        Asset parent = optionalAsset(request.parentCode());
        requireParentInSite(parent, site);
        requireDepth(parent, null);
        Asset value = new Asset();
        value.setSiteId(site.getId());
        value.setParentId(parent == null ? null : parent.getId());
        value.setCode(request.code());
        value.setName(request.name().trim());
        value.setType(blankToNull(request.type()));
        value.setStatus(request.status());
        assets.insert(value);
        return assetView(assets.selectById(value.getId()), site.getCode(), parent == null ? null : parent.getCode());
    }

    @Transactional
    public AssetView updateAsset(String code, SaveAssetRequest request) {
        requireSameCode(code, request.code());
        Asset value = requireAsset(code);
        Site site = requireSite(request.siteCode());
        if (!site.getId().equals(value.getSiteId())) {
            throw ApiException.conflict("资产不能移动到其他站点");
        }
        Asset parent = optionalAsset(request.parentCode());
        requireParentInSite(parent, site);
        requireDepth(parent, value.getId());
        value.setParentId(parent == null ? null : parent.getId());
        value.setName(request.name().trim());
        value.setType(blankToNull(request.type()));
        value.setStatus(request.status());
        value.setUpdatedAt(now());
        assets.updateById(value);
        return assetView(value, site.getCode(), parent == null ? null : parent.getCode());
    }

    public void deleteAsset(String code) {
        Asset value = requireAsset(code);
        if (assets.exists(new LambdaQueryWrapper<Asset>().eq(Asset::getParentId, value.getId()))) {
            throw ApiException.conflict("资产下仍有子资产，不能删除");
        }
        if (devices.exists(new LambdaQueryWrapper<Device>().eq(Device::getAssetId, value.getId()))) {
            throw ApiException.conflict("资产下仍有设备，不能删除");
        }
        assets.deleteById(value.getId());
    }

    public Site requireSite(String code) {
        Site value = sites.selectOne(new LambdaQueryWrapper<Site>().eq(Site::getCode, code));
        if (value == null) {
            throw ApiException.notFound("站点不存在: " + code);
        }
        return value;
    }

    public Asset optionalAsset(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return requireAsset(code);
    }

    /** 当前租户内按 id 取资产；设备归属展示使用。 */
    public Asset optionalAssetById(long id) {
        return assets.selectById(id);
    }

    private Customer requireCustomer(String code) {
        Customer value = customers.selectOne(new LambdaQueryWrapper<Customer>().eq(Customer::getCode, code));
        if (value == null) throw ApiException.notFound("客户不存在: " + code);
        return value;
    }

    private Customer optionalCustomer(String code) {
        return code == null || code.isBlank() ? null : requireCustomer(code);
    }

    private Project requireProject(String code) {
        Project value = projects.selectOne(new LambdaQueryWrapper<Project>().eq(Project::getCode, code));
        if (value == null) throw ApiException.notFound("项目不存在: " + code);
        return value;
    }

    private Asset requireAsset(String code) {
        Asset value = assets.selectOne(new LambdaQueryWrapper<Asset>().eq(Asset::getCode, code));
        if (value == null) throw ApiException.notFound("资产不存在: " + code);
        return value;
    }

    private void requireParentInSite(Asset parent, Site site) {
        if (parent != null && !parent.getSiteId().equals(site.getId())) {
            throw ApiException.badRequest("父资产必须属于同一站点");
        }
    }

    private void requireDepth(Asset parent, Long movingId) {
        int depth = 1;
        Asset current = parent;
        while (current != null) {
            if (current.getId().equals(movingId)) {
                throw ApiException.conflict("资产层级不能形成环");
            }
            if (++depth > MAX_ASSET_DEPTH) {
                throw ApiException.conflict("资产层级最多 " + MAX_ASSET_DEPTH + " 层");
            }
            current = current.getParentId() == null ? null : assets.selectById(current.getParentId());
        }
    }

    private void ensureCustomerCodeAvailable(String code, Long excludingId) {
        LambdaQueryWrapper<Customer> query = new LambdaQueryWrapper<Customer>().eq(Customer::getCode, code);
        if (excludingId != null) query.ne(Customer::getId, excludingId);
        ensureAvailable(customers.selectOne(query), "客户编码已存在: " + code);
    }

    private void ensureProjectCodeAvailable(String code, Long excludingId) {
        LambdaQueryWrapper<Project> query = new LambdaQueryWrapper<Project>().eq(Project::getCode, code);
        if (excludingId != null) query.ne(Project::getId, excludingId);
        ensureAvailable(projects.selectOne(query), "项目编码已存在: " + code);
    }

    private void ensureSiteCodeAvailable(String code, Long excludingId) {
        LambdaQueryWrapper<Site> query = new LambdaQueryWrapper<Site>().eq(Site::getCode, code);
        if (excludingId != null) query.ne(Site::getId, excludingId);
        ensureAvailable(sites.selectOne(query), "站点编码已存在: " + code);
    }

    private void ensureAssetCodeAvailable(String code, Long excludingId) {
        LambdaQueryWrapper<Asset> query = new LambdaQueryWrapper<Asset>().eq(Asset::getCode, code);
        if (excludingId != null) query.ne(Asset::getId, excludingId);
        ensureAvailable(assets.selectOne(query), "资产编码已存在: " + code);
    }

    private static void ensureAvailable(Object existing, String message) {
        if (existing != null) throw ApiException.conflict(message);
    }

    private static void requireSameCode(String pathCode, String bodyCode) {
        if (!pathCode.equals(bodyCode)) {
            throw ApiException.badRequest("编码创建后不可修改");
        }
    }

    private static void requireTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (ZoneRulesException e) {
            throw ApiException.badRequest("时区不存在: " + timezone);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private ProjectView projectView(Project p, Customer c) {
        return new ProjectView(p.getCode(), p.getName(), c == null ? null : c.getCode(), p.getStatus(),
                Boolean.TRUE.equals(p.getIsDefault()), p.getCreatedAt());
    }

    private SiteView siteView(Site s, String projectCode) {
        return new SiteView(s.getCode(), s.getName(), projectCode, s.getTimezone(), s.getStatus(),
                Boolean.TRUE.equals(s.getIsDefault()), s.getCreatedAt());
    }

    private AssetView assetView(Asset a, String siteCode, String parentCode) {
        return new AssetView(a.getCode(), a.getName(), siteCode, parentCode, a.getType(), a.getStatus(), a.getCreatedAt());
    }

    private Map<Long, String> projectCodes(List<Long> ids) {
        if (ids.isEmpty()) return new HashMap<>();
        Map<Long, String> result = new HashMap<>();
        projects.selectBatchIds(ids).forEach(p -> result.put(p.getId(), p.getCode()));
        return result;
    }

    private Map<Long, String> siteCodes(List<Long> ids) {
        if (ids.isEmpty()) return new HashMap<>();
        Map<Long, String> result = new HashMap<>();
        sites.selectBatchIds(ids).forEach(s -> result.put(s.getId(), s.getCode()));
        return result;
    }

    private static Map<Long, String> codes(List<Customer> rows) {
        Map<Long, String> result = new HashMap<>();
        rows.forEach(c -> result.put(c.getId(), c.getCode()));
        return result;
    }
}
