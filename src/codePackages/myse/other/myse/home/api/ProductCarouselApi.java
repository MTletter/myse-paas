package other.myse.home.api;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.api.annotations.RequestMethod;
import com.rkhd.platform.sdk.api.annotations.RestApi;
import com.rkhd.platform.sdk.api.annotations.RestBeanParam;
import com.rkhd.platform.sdk.api.annotations.RestMapping;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.QueryResult;
import com.rkhd.platform.sdk.model.XObject;
import com.rkhd.platform.sdk.service.XObjectService;
import com.rkhd.platform.sdk.service.XoqlService;
import other.myse.home.utils.HttpUtil;
import other.myse.home.utils.PermissionChangeGuard;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页产品轮播查询接口
 * <p>
 * 查询 productCarousel__c（产品轮播）所有数据，不分页，按创建时间倒序返回。
 * 前端无需传参，直接调用即可获取全部轮播配置（标题、图片、链接、描述、审批状态）。
 * </p>
 * <p>销售易 API 路径：POST /rest/data/v2.0/scripts/api/home/carousel/list</p>
 */
@RestApi(baseUrl = "/home")
public class ProductCarouselApi {

    private static final Logger logger = LoggerFactory.getLogger();

    @RestMapping(value = "/carousel/list", method = RequestMethod.POST)
    public String execute() {
        try {
            // 权限变化守卫：命中租户级缓存的变更标记 → 返回权限变化，不进入业务逻辑
            String guard = PermissionChangeGuard.checkPermissionChanged("ProductCarouselApi");
            if (guard != null) {
                return guard;
            }
            List<JSONObject> list = new ArrayList<>();

            String sql = "SELECT id, name, picture__c, url__c, description__c, approvalStatus "
                    + "FROM productCarousel__c ORDER BY createdAt DESC LIMIT 300";

            try {
                QueryResult<JSONObject> result = XoqlService.instance().query(sql);
                if (result != null && result.getSuccess() && result.getRecords() != null) {
                    for (JSONObject o : result.getRecords()) {
                        list.add(convert(o));
                    }
                } else if (result != null && !result.getSuccess()) {
                    logger.error("ProductCarousel 查询返回 success=false");
                }
            } catch (Exception e) {
                logger.error("ProductCarousel 查询异常, sql=" + sql, e);
            }

            JSONObject data = new JSONObject();
            data.put("list", list);

            logger.info("ProductCarousel 查询完成, 返回 " + list);
            return HttpUtil.success(data).toJSONString();

        } catch (Exception e) {
            logger.error("ProductCarousel 执行异常", e);
            return HttpUtil.fail("INTERNAL_ERROR", e.getMessage()).toJSONString();
        }
    }

    private JSONObject convert(JSONObject r) {
        JSONObject item = new JSONObject();
        item.put("id", r.getLong("id"));
        item.put("title", r.getString("name"));
        // 图片字段（type=29 图片类型，List<Map>）原样返回，供前端图片组件解析
        item.put("picture", r.getJSONArray("picture__c"));
        item.put("url", r.getString("url__c"));
        item.put("description", r.getString("description__c"));
//        item.put("approvalStatus", r.getString("approvalStatus"));
        return item;
    }



    public static void main(String[] args) {
        new ProductCarouselApi().execute();
    }
}
