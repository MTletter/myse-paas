package other.myse.csv;

import com.alibaba.fastjson.JSONObject;
import com.rkhd.platform.sdk.data.model.Account;
import com.rkhd.platform.sdk.data.model.Department;
import com.rkhd.platform.sdk.data.model.Product;
import com.rkhd.platform.sdk.data.model.User;
import com.rkhd.platform.sdk.log.Logger;
import com.rkhd.platform.sdk.log.LoggerFactory;
import com.rkhd.platform.sdk.model.OperateResult;
import com.rkhd.platform.sdk.service.XObjectService;
import other.xsy.myse.apis.Myse13CustomerMasterApi;

public class test {

    private static final Logger logger = LoggerFactory.getLogger();

    public static void main(String[] args) throws Exception {

//        User user=new User();
//        user.setId(4568448612042754L);
//        user=XObjectService.instance().get(user,true);
//        Department de=new Department();
//        de.setId(4568447693734866L);
//        de=XObjectService.instance().get(de,true);
        Product pro=new Product();
        pro.setId(4568464207841294L);
        pro=XObjectService.instance().get(pro,true);
    }
}
