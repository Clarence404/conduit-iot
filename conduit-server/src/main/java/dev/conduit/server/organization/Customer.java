package dev.conduit.server.organization;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** 租户内部的下游客户或业务单位；不是独立安全边界。 */
@Data
@TableName("customer")
public class Customer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
