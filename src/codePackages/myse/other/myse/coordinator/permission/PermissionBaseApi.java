package other.myse.coordinator.permission;

import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.model.QueryResult;
import other.myse.coordinator.api.UserPermissionBaseApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限管理模块（permission 子包）共享基类
 *
 * <p>继承 {@link UserPermissionBaseApi} 复用全部关联查询/工具方法，
 * 本类只补充"是否内部用户"过滤的 User 查询能力。</p>
 *
 * <p>包结构说明：coordinator（账户权限管理）按模块拆子包——
 * permission=权限管理（用户视角），后续 approval=待审批列表、
 * roledef=角色权限定义、account=账户访问管理。</p>
 */
public abstract class PermissionBaseApi extends UserPermissionBaseApi {

    /**
     * entityBelongType id：内部用户（员工）。沙盒实测值，生产租户需核对。
     * 数据来源：user.entityType referTo entityBelongType，
     * 沙盒中 990641599905821=内部员工、990641599905820=外部门户用户。
     */
    protected static final String ENTITY_TYPE_INTERNAL = "990641599905821";
    /** entityBelongType id：外部用户（门户/联系人） */
    protected static final String ENTITY_TYPE_EXTERNAL = "990641599905820";

    /**
     * 按 姓名/电话/邮箱/是否内部用户 组合条件查询 User，返回匹配的用户 id 集合。
     * 所有条件之间为 AND（同一用户需同时满足），单个文本条件内部为模糊匹配。
     *
     * @param name       姓名模糊（可空）
     * @param phone      电话模糊（可空）
     * @param email      邮箱模糊（可空）
     * @param isInternal 是否内部用户（可空；true=内部 false=外部）
     * @return 匹配的 user id 集合；无任何条件时返回 null（表示不过滤）
     */
    protected Set<String> queryMatchedUserIdsEx(String name, String phone, String email, Boolean isInternal) {
        boolean hasText = (name != null && !name.trim().isEmpty())
                || (phone != null && !phone.trim().isEmpty())
                || (email != null && !email.trim().isEmpty());
        if (!hasText && isInternal == null) {
            return null; // 无用户侧条件，调用方不加 user__c 过滤
        }

        // 文本条件：姓名/电话/邮箱任一命中即算该用户匹配（与原列表行为一致，OR）
        // 再与 entityType 条件 AND
        Set<String> ids = new HashSet<>();
        List<String> orConds = new ArrayList<>();
        if (name != null && !name.trim().isEmpty()) {
            orConds.add("name LIKE '%" + escapeLike(name.trim()) + "%'");
        }
        if (phone != null && !phone.trim().isEmpty()) {
            orConds.add("phone LIKE '%" + escapeLike(phone.trim()) + "%'");
        }
        if (email != null && !email.trim().isEmpty()) {
            orConds.add("personalEmail LIKE '%" + escapeLike(email.trim()) + "%'");
        }

        StringBuilder sql = new StringBuilder("SELECT id FROM user WHERE ");
        if (!orConds.isEmpty()) {
            sql.append("(").append(String.join(" OR ", orConds)).append(")");
        } else {
            sql.append("1=1");
        }
        if (isInternal != null) {
            sql.append(" AND entityType = ")
                    .append(isInternal ? ENTITY_TYPE_INTERNAL : ENTITY_TYPE_EXTERNAL);
        }
        QueryResult<JSONObject> rt = queryWithLog(TAG_BASE, "user(含内部用户过滤)", sql.toString());
        if (rt != null && rt.getRecords() != null) {
            for (Object o : rt.getRecords()) {
                String id = ((JSONObject) o).getString("id");
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
}
