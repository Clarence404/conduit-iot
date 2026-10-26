package dev.conduit.server.device;

import dev.conduit.server.device.DeviceDtos.CreateDeviceRequest;
import dev.conduit.server.device.DeviceDtos.AssignDeviceRequest;
import dev.conduit.server.device.DeviceDtos.DeviceAssignmentView;
import dev.conduit.server.device.DeviceDtos.DeviceCreated;
import dev.conduit.server.device.DeviceDtos.DeviceView;
import dev.conduit.server.device.DeviceDtos.SecretView;
import dev.conduit.server.device.DeviceDtos.UpdateDeviceRequest;
import dev.conduit.server.web.PageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备管理 REST 接口（/api/v1/devices），给 UI 的设备列表与详情页使用；业务逻辑在 DeviceService。
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService service;

    public DeviceController(DeviceService service) {
        this.service = service;
    }

    /**
     * 分页列出设备。可选筛选：productKey、keyword（名称或 deviceKey）、status（ENABLED|DISABLED）、
     * online（true|false）、reported（RECENT|STALE|NEVER，RECENT 为最近 1 小时内有上报）、
     * createdFrom（毫秒，含）、createdTo（毫秒，不含）。非法参数返回 400；online / reported 依赖 Redis，Redis 不可用时返回 503。
     */
    @GetMapping
    public PageView<DeviceView> list(@RequestParam(required = false) String productKey,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String online,
                                     @RequestParam(required = false) String reported,
                                     @RequestParam(required = false) String createdFrom,
                                     @RequestParam(required = false) String createdTo,
                                     @RequestParam(defaultValue = "1") @Min(1) int page,
                                     @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return service.list(DeviceFilter.parse(productKey, keyword, status, online, reported, createdFrom, createdTo),
                page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceCreated create(@Valid @RequestBody CreateDeviceRequest request) {
        return service.create(request);
    }

    @GetMapping("/{deviceKey}")
    public DeviceView get(@PathVariable String deviceKey) {
        return service.get(deviceKey);
    }

    @PutMapping("/{deviceKey}")
    public DeviceView update(@PathVariable String deviceKey, @Valid @RequestBody UpdateDeviceRequest request) {
        return service.update(deviceKey, request);
    }

    @GetMapping("/{deviceKey}/assignment")
    public DeviceAssignmentView assignment(@PathVariable String deviceKey) {
        return service.assignment(deviceKey);
    }

    @PutMapping("/{deviceKey}/assignment")
    public DeviceAssignmentView assign(@PathVariable String deviceKey,
                                       @Valid @RequestBody AssignDeviceRequest request) {
        return service.assign(deviceKey, request);
    }

    @PostMapping("/{deviceKey}/reset-secret")
    public SecretView resetSecret(@PathVariable String deviceKey) {
        return service.resetSecret(deviceKey);
    }

    @DeleteMapping("/{deviceKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String deviceKey) {
        service.delete(deviceKey);
    }
}
