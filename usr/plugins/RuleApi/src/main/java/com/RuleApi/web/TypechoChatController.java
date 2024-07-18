package com.RuleApi.web;

import com.RuleApi.annotation.LoginRequired;
import com.RuleApi.common.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.RuleApi.entity.*;
import com.RuleApi.service.*;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 控制层
 * TypechoChatController
 * @author buxia97
 * @date 2023/01/10
 */
@Controller
@RequestMapping(value = "/typechoChat")
public class TypechoChatController {

    @Autowired
    TypechoChatService service;

    @Autowired
    TypechoChatMsgService chatMsgService;

    @Autowired
    private SecurityService securityService;

    @Autowired
    private TypechoApiconfigService apiconfigService;

    @Autowired
    private TypechoUsersService usersService;

    @Value("${web.prefix}")
    private String dataprefix;

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private PushService pushService;

    RedisHelp redisHelp =new RedisHelp();
    ResultAll Result = new ResultAll();
    UserStatus UStatus = new UserStatus();
    baseFull baseFull = new baseFull();
    EditFile editFile = new EditFile();

    /***
     * 获取私聊聊天室（没有则自动新增）
     */
    @RequestMapping(value = "/getPrivateChat")
    @ResponseBody
    @LoginRequired(purview = "0")
    public String getPrivateChat(@RequestParam(value = "token", required = false) String  token,
                                 @RequestParam(value = "touid", required = false) Integer  touid) {
        try{
            if(touid==null||touid<1){
                return Result.getResultJson(0,"参数不正确",null);
            }
            Integer chatid = null;
            Map map =redisHelp.getMapValue(this.dataprefix+"_"+"userInfo"+token,redisTemplate);
            Integer uid =Integer.parseInt(map.get("uid").toString());
            //登录情况下，刷聊天数据
            TypechoApiconfig apiconfig = UStatus.getConfig(this.dataprefix,apiconfigService,redisTemplate);
            if(apiconfig.getBanRobots().equals(1)) {
                String isSilence = redisHelp.getRedis(this.dataprefix+"_"+uid+"_silence",redisTemplate);
                if(isSilence!=null){
                    return Result.getResultJson(0,"你的操作太频繁了，请稍后再试",null);
                }
                String isRepeated = redisHelp.getRedis(this.dataprefix+"_"+uid+"_getChat",redisTemplate);
                if(isRepeated==null){
                    redisHelp.setRedis(this.dataprefix+"_"+uid+"_getChat","2",1,redisTemplate);
                }else{
                    Integer frequency = Integer.parseInt(isRepeated) + 1;
                    if(frequency==4){
                        securityService.safetyMessage("用户ID："+uid+"，在聊天发起接口疑似存在攻击行为，请及时确认处理。","system");
                        redisHelp.setRedis(this.dataprefix+"_"+uid+"_silence","1",apiconfig.getSilenceTime(),redisTemplate);
                        return Result.getResultJson(0,"你的操作过于频繁，已被禁用15分钟聊天室！",null);
                    }else{
                        redisHelp.setRedis(this.dataprefix+"_"+uid+"_isSendMsg",frequency.toString(),5,redisTemplate);
                    }
                }
            }

            //攻击拦截结束

            //判断是否有聊天室存在(自己发起的聊天)
            TypechoChat chat = new TypechoChat();
            chat.setUid(uid);
            chat.setToid(touid);
            List<TypechoChat> list = service.selectList(chat);
            if(list.size()>0){
                chatid = list.get(0).getId();
            }else{
                //判断对方发起的聊天
                chat.setUid(touid);
                chat.setToid(uid);
                list = service.selectList(chat);
                if(list.size()>0){
                    chatid = list.get(0).getId();
                }
            }
            //如果未聊天过，则创建聊天室
            if(chatid==null){
                //判断用户经验值

                Integer chatMinExp = apiconfig.getChatMinExp();
                TypechoUsers curUser = usersService.selectByKey(uid);
                Integer Exp = curUser.getExperience();
                if(Exp < chatMinExp){
                    return Result.getResultJson(0,"聊天最低要求经验值为"+chatMinExp+",你当前经验值"+Exp,null);
                }
                TypechoChat insert = new TypechoChat();
                insert.setUid(uid);
                insert.setToid(touid);
                Long date = System.currentTimeMillis();
                String created = String.valueOf(date).substring(0,10);
                insert.setCreated(Integer.parseInt(created));
                insert.setLastTime(Integer.parseInt(created));
                insert.setType(0);
                service.insert(insert);
                chatid = insert.getId();
            }
            JSONObject response = new JSONObject();
            response.put("code" , 1);
            response.put("data" , chatid);
            response.put("msg"  , "");
            return response.toString();
        }catch (Exception e){
            e.printStackTrace();
            return Result.getResultJson(0,"接口请求异常，请联系管理员",null);
        }

    }
    /***
     * 发送消息
     */
    @RequestMapping(value = "/sendMsg")
    @ResponseBody
    @LoginRequired(purview = "0")
    public String sendMsg(@RequestParam(value = "token", required = false) String  token,
                          @RequestParam(value = "chatid", required = false) Integer  chatid,
                          @RequestParam(value = "msg", required = false) String  msg,
                          @RequestParam(value = "type", required = false, defaultValue = "0") Integer  type,
                          @RequestParam(value = "url", required = false) String  url) {
        try{
            if(chatid==null||type==null){
                return Result.getResultJson(0,"参数不正确",null);
            }
            if(type<0){
                return Result.getResultJson(0,"参数不正确",null);
            }
            if(type.equals(0)){
                if(msg.length()<1){
                    return Result.getResultJson(0,"消息内容不能为空",null);
                }
                if(msg.length()>1500){
                    return Result.getResultJson(0,"最大消息内容为1000字符",null);
                }
            }
            Map map =redisHelp.getMapValue(this.dataprefix+"_"+"userInfo"+token,redisTemplate);
            Integer uid =Integer.parseInt(map.get("uid").toString());
            String group = map.get("group").toString();
            //禁言判断

            String isSilence = redisHelp.getRedis(this.dataprefix+"_"+uid+"_silence",redisTemplate);
            if(isSilence!=null){
                return Result.getResultJson(0,"你的操作太频繁了，请稍后再试",null);
            }

            //登录情况下，刷数据攻击拦截
            TypechoApiconfig apiconfig = UStatus.getConfig(this.dataprefix,apiconfigService,redisTemplate);
            if(apiconfig.getBanRobots().equals(1)) {
                String isRepeated = redisHelp.getRedis(this.dataprefix+"_"+uid+"_isSendMsg",redisTemplate);
                if(isRepeated==null){
                    redisHelp.setRedis(this.dataprefix+"_"+uid+"_isSendMsg","1",1,redisTemplate);
                }else{
                    Integer frequency = Integer.parseInt(isRepeated) + 1;
                    if(frequency==4){
                        securityService.safetyMessage("用户ID："+uid+"，在聊天发送消息接口疑似存在攻击行为，请及时确认处理。","system");
                        redisHelp.setRedis(this.dataprefix+"_"+uid+"_silence","1",apiconfig.getSilenceTime(),redisTemplate);
                        return Result.getResultJson(0,"你的发言过于频繁，已被禁言十分钟！",null);
                    }else{
                        redisHelp.setRedis(this.dataprefix+"_"+uid+"_isSendMsg",frequency.toString(),5,redisTemplate);
                    }
                    return Result.getResultJson(0,"你的操作太频繁了",null);
                }
            }


            //攻击拦截结束
            if(baseFull.haveCode(msg).equals(1)){
                return Result.getResultJson(0,"消息内容存在敏感代码",null);
            }
            TypechoChat chat = service.selectByKey(chatid);
            if(chat==null){
                return Result.getResultJson(0,"聊天室不存在",null);
            }
            if(!chat.getBan().equals(0)){

                if(group.equals("administrator")&&group.equals("editor")){
                    if(chat.getType().equals(0)){
                        return Result.getResultJson(0,"该聊天室已开启屏蔽机制",null);
                    }else{
                        return Result.getResultJson(0,"管理员已开启禁言",null);
                    }
                }

            }

            //判断用户经验值
            Integer chatMinExp = apiconfig.getChatMinExp();
            TypechoUsers curUser = usersService.selectByKey(uid);
            Integer Exp = curUser.getExperience();
            if(Exp < chatMinExp){
                return Result.getResultJson(0,"聊天最低要求经验值为"+chatMinExp+",你当前经验值"+Exp,null);
            }
            if(type.equals(0)){
                if(msg.length()>1500){
                    return Result.getResultJson(0,"最大发言字数不超过1500",null);
                }
                //违禁词拦截

                String forbidden = apiconfig.getForbidden();
                Integer intercept = 0;
                Integer isForbidden = baseFull.getForbidden(forbidden,msg);
                if(isForbidden.equals(1)){
                    intercept = 1;
                }
                if(intercept.equals(1)){
                    //以十分钟为检测周期，违禁一次刷新一次，等于4次则禁言
                    String isIntercept = redisHelp.getRedis(this.dataprefix+"_"+uid+"_isIntercept",redisTemplate);
                    if(isIntercept==null){
                        redisHelp.setRedis(this.dataprefix+"_"+uid+"_isIntercept","1",600,redisTemplate);
                    }else{
                        Integer frequency = Integer.parseInt(isIntercept) + 1;
                        if(frequency==4){
                            securityService.safetyMessage("用户ID："+uid+"，在聊天发送消息接口多次触发违禁，请及时确认处理。","system");
                            redisHelp.setRedis(this.dataprefix+"_"+uid+"_silence","1",apiconfig.getInterceptTime(),redisTemplate);
                            return Result.getResultJson(0,"您多次发送违禁内容，已被限制功能1小时",null);
                        }else{
                            redisHelp.setRedis(this.dataprefix+"_"+uid+"_isIntercept",frequency.toString(),600,redisTemplate);
                        }
                    }
                    return Result.getResultJson(0,"消息存在违禁词",null);
                }
                //违禁词拦截结束
            }

            Long date = System.currentTimeMillis();
            String created = String.valueOf(date).substring(0,10);
            TypechoChatMsg msgbox = new TypechoChatMsg();
            msgbox.setCid(chatid);
            msgbox.setUid(uid);
            msgbox.setText(msg);
            msgbox.setCreated(Integer.parseInt(created));
            msgbox.setUrl(url);
            msgbox.setType(type);
            int rows = chatMsgService.insert(msgbox);
            //更新聊天室最后消息时间（还有未读消息，只针对私聊）
            TypechoChat newChat = new TypechoChat();
            newChat.setId(chatid);
            newChat.setLastTime(Integer.parseInt(created));
            if(chat.getType().equals(0)){
                Integer chatUid = chat.getUid();
                Integer touid = chat.getToid();
                Integer otherUnRead = chat.getOtherUnRead();
                Integer myUnRead = chat.getMyUnRead();
                if(uid.equals(chatUid)){
                    otherUnRead++;
                    newChat.setOtherUnRead(otherUnRead);
                }else{
                    myUnRead++;
                    newChat.setMyUnRead(myUnRead);
                }
                //将聊天未读消息载入redis缓存（节约性能）
                String unRead = redisHelp.getRedis(this.dataprefix+"_unReadMsg_"+touid,redisTemplate);
                if(!uid.equals(chatUid)){
                    unRead = redisHelp.getRedis(this.dataprefix+"_unReadMsg_"+chatUid,redisTemplate);
                }

                if(unRead==null){
                    if(uid.equals(chatUid)){
                        redisHelp.setRedis(this.dataprefix+"_unReadMsg_"+touid,otherUnRead.toString(),600,redisTemplate);
                        redisHelp.delete(this.dataprefix+"_"+"unreadNum_"+touid,redisTemplate);
                    }else{
                        redisHelp.setRedis(this.dataprefix+"_unReadMsg_"+chatUid,myUnRead.toString(),600,redisTemplate);
                        redisHelp.delete(this.dataprefix+"_"+"unreadNum_"+chatUid,redisTemplate);
                    }
                }else{
                    Integer unReadNum = Integer.parseInt(unRead);
                    unReadNum = unReadNum + 1;
                    if(uid.equals(chatUid)){
                        redisHelp.setRedis(this.dataprefix+"_unReadMsg_"+touid,unReadNum.toString(),600,redisTemplate);
                        redisHelp.delete(this.dataprefix+"_"+"unreadNum_"+touid,redisTemplate);
                    }else{
                        redisHelp.setRedis(this.dataprefix+"_unReadMsg_"+chatUid,unReadNum.toString(),600,redisTemplate);
                        redisHelp.delete(this.dataprefix+"_"+"unreadNum_"+chatUid,redisTemplate);
                    }

                }


            }
            service.update(newChat);
            redisHelp.deleteKeysWithPattern("*"+this.dataprefix+"_"+"msgList_"+chatid+"*",redisTemplate,this.dataprefix);

            if(chat.getType().equals(0)) {
                redisHelp.deleteKeysWithPattern("*"+this.dataprefix + "_" + "myChat_" + chat.getToid() + "*", redisTemplate,this.dataprefix);
            }
            redisHelp.deleteKeysWithPattern("*"+this.dataprefix+"_"+"myChat_"+chat.getUid()+"*",redisTemplate,this.dataprefix);
            JSONObject response = new JSONObject();
            response.put("code" , rows);
            response.put("msg"  , rows > 0 ? "发送成功" : "发送失败");
            return response.toString();
        }catch (Exception e){
            e.printStackTrace();
            return Result.getResultJson(0,"接口请求异常，请联系管理员",null);
        }


    }
    /***
     * 我参与的私聊
     */
    @RequestMapping(value = "/myChat")
    @ResponseBody
    @LoginRequired(purview = "0")
    public String myCaht (@RequestParam(value = "page"        , required = false, defaultValue = "1") Integer page,
                          @RequestParam(value = "token", required = false) String  token,
                          @RequestParam(value = "order", required = false) String  order,
                          @RequestParam(value = "limit"       , required = false, defaultValue = "15") Integer limit) {
        if(limit>50){
            limit = 50;
        }
        if(order==null){
            order = "lastTime";
        }
        Map map = redisHelp.getMapValue(this.dataprefix+"_"+"userInfo"+token,redisTemplate);

        Integer uid =Integer.parseInt(map.get("uid").toString());
        //查询uid时，同时查询toid
        TypechoChat query = new TypechoChat();
        query.setUid(uid);
        query.setType(0);
        List jsonList = new ArrayList();
        List cacheList = redisHelp.getList(this.dataprefix+"_"+"myChat_"+uid+"_"+page+"_"+limit,redisTemplate);
        Integer total = service.total(query);
        try{
            if(cacheList.size()>0){
                jsonList = cacheList;
            }else{
                TypechoApiconfig apiconfig = UStatus.getConfig(this.dataprefix,apiconfigService,redisTemplate);

                PageList<TypechoChat> pageList = service.selectPage(query, page, limit,order,null);
                List<TypechoChat> list = pageList.getList();
                if(list.size() < 1){
                    JSONObject noData = new JSONObject();
                    noData.put("code" , 1);
                    noData.put("msg"  , "");
                    noData.put("data" , new ArrayList());
                    noData.put("count", 0);
                    noData.put("total", total);
                    return noData.toString();
                }
                for (int i = 0; i < list.size(); i++) {
                    Map json = JSONObject.parseObject(JSONObject.toJSONString(list.get(i)), Map.class);


                    TypechoChat chat = list.get(i);

                    //获取最新聊天消息
                    Integer chatid = chat.getId();
                    TypechoChatMsg msg = new TypechoChatMsg();
                    msg.setCid(chatid);
                    List<TypechoChatMsg> msgList = chatMsgService.selectList(msg);
                    if(msgList.size()>0) {
                        Map lastMsg = JSONObject.parseObject(JSONObject.toJSONString(msgList.get(0)), Map.class);
                        json.put("lastMsg",lastMsg);
                    }
                    Integer msgNum = chatMsgService.total(msg);
                    json.put("msgNum",msgNum);

                    Integer userid = chat.getUid();
                    if(userid.equals(uid)){
                        userid = chat.getToid();
                    }else{
                        userid = chat.getUid();
                    }
                    TypechoUsers user = usersService.selectByKey(userid);
                    //获取用户信息
                    Map userJson = new HashMap();
                    if(user!=null){
                        String name = user.getName();
                        if(user.getScreenName()!=null){
                            name = user.getScreenName();
                        }
                        userJson.put("name", name);
                        userJson.put("groupKey", user.getGroupKey());

                        if(user.getAvatar()==null){
                            if(user.getMail()!=null){
                                String mail = user.getMail();
                                if(mail.indexOf("@qq.com") != -1){
                                    String qq = mail.replace("@qq.com","");
                                    userJson.put("avatar", "https://q1.qlogo.cn/g?b=qq&nk="+qq+"&s=640");
                                }else{
                                    userJson.put("avatar", baseFull.getAvatar(apiconfig.getWebinfoAvatar(), mail));
                                }
                                //json.put("avatar",baseFull.getAvatar(apiconfig.getWebinfoAvatar(),user.getMail()));
                            }else{
                                userJson.put("avatar",apiconfig.getWebinfoAvatar()+"null");
                            }
                        }else{
                            userJson.put("avatar", user.getAvatar());
                        }
                        userJson.put("uid", user.getUid());
                        userJson.put("customize", user.getCustomize());
                        userJson.put("experience",user.getExperience());
                        userJson.put("introduce", user.getIntroduce());
                        //判断是否为VIP
                        userJson.put("vip", user.getVip());
                        userJson.put("isvip", 0);
                        Long date = System.currentTimeMillis();
                        String curTime = String.valueOf(date).substring(0, 10);
                        Integer viptime  = user.getVip();
                        if(viptime>Integer.parseInt(curTime)||viptime.equals(1)){
                            userJson.put("isvip", 1);
                        }

                    }else{
                        userJson.put("name", "用户已注销");
                        userJson.put("groupKey", "");
                        userJson.put("avatar", apiconfig.getWebinfoAvatar() + "null");
                    }
                    json.put("userJson",userJson);
                    jsonList.add(json);
                }
                redisHelp.delete(this.dataprefix+"_"+"myChat_"+uid+"_"+page+"_"+limit,redisTemplate);
                redisHelp.setList(this.dataprefix+"_"+"myChat_"+uid+"_"+page+"_"+limit,jsonList,120,redisTemplate);
            }
        }catch (Exception e){
            e.printStackTrace();
            if(cacheList.size()>0){
                jsonList = cacheList;
            }
        }
        JSONObject response = new JSONObject();
        response.put("code" , 1);
        response.put("msg"  , "");
        response.put("data" , jsonList);
        response.put("count", jsonList.size());
        response.put("total", total);
        return response.toString();

    }
    /***
     * 聊天消息单独已读
     */
    @RequestMapping(value = "/msgSetRead")
    @ResponseBody
    @LoginRequired(purview = "0")
    public String msgSetRead ( @RequestParam(value = "chatid", required = false) Integer  chatid,
                               @RequestParam(value = "token", required = false) String  token) {
        if(chatid==null){
            return Result.getResultJson(0,"参数不正确",null);
        }
        Map map = redisHelp.getMapValue(this.dataprefix + "_" + "userInfo" + token, redisTemplate);
        Integer loguid =Integer.parseInt(map.get("uid").toString());
        TypechoApiconfig apiconfig = UStatus.getConfig(this.dataprefix,apiconfigService,redisTemplate);
        TypechoChat chat = service.selectByKey(chatid);
        if(chat==null){
            return Result.getResultJson(0,"聊天室不存在",null);
        }
        try{
            /**更新聊天室未读消息**/
            Integer uid = chat.getUid();
            Integer touid = chat.getToid();
            TypechoChat newChat = new TypechoChat();
            newChat.setId(chatid);
            //如果聊天室是我发起
            if(loguid.equals(uid)){
                if(chat.getMyUnRead() > 0 ){
                    newChat.setMyUnRead(0);
                    service.update(newChat);
                    redisHelp.deleteKeysWithPattern("*"+this.dataprefix+"_"+"myChat_"+uid+"*",redisTemplate,this.dataprefix);
                    //清理总未读消息
                    String unRead = redisHelp.getRedis(this.dataprefix+"_unReadMsg_"+loguid,redisTemplate);
                    Integer unReadNum = Integer.parseInt(unRead);
                    unReadNum = unReadNum - chat.getMyUnRead();
                    if(unReadNum > 0){
                        redisHelp.setRedis(this.dataprefix+"_unReadMsg_"+uid,unReadNum.toString(),600,redisTemplate);
                    }else{
                        redisHelp.setRedis(this.dataprefix+"_unReadMsg_"+uid,"0",600,redisTemplate);
                    }
                    redisHelp.delete(this.dataprefix+"_"+"unreadNum_"+uid,redisTemplate);
                    redisHelp.deleteKeysWithPattern("*"+this.dataprefix+"_"+"myChat_"+uid+"*",redisTemplate,this.dataprefix);

                }
            }
            //如果聊天室对方发起
            if(loguid.equals(touid)){
                if(chat.getOtherUnRead() > 0 ){
                    newChat.setOtherUnRead(0);
                    service.update(newChat);
                    redisHelp.deleteKeysWithPattern("*"+this.dataprefix+"_"+"myChat_"+touid+"*",redisTemplate,this.dataprefix);
                    //清理总未读消息
                    String unRead = redisHelp.getRedis(this.dataprefix+"_unReadMsg_"+touid,redisTemplate);
                    Integer unReadNum = Integer.parseInt(unRead);
                    unReadNum = unReadNum - chat.getOtherUnRead();
                    if(unReadNum > 0){
                        redisHelp.setRedis(this.dataprefix+"_unReadMsg_"+touid,unReadNum.toString(),600,redisTemplate);
                    }else{
                        redisHelp.setRedis(this.dataprefix+"_unReadMsg_"+touid,"0",600,redisTemplate);
                    }
                    redisHelp.delete(this.dataprefix+"_"+"unreadNum_"+loguid,redisTemplate);
                    redisHelp.deleteKeysWithPattern("*"+this.dataprefix+"_"+"myChat_"+loguid+"*",redisTemplate,this.dataprefix);

                }
            }
            return Result.getResultJson(0,"操作成功",null);
        }catch (Exception e){
            e.printStackTrace();
            return Result.getResultJson(0,"接口请求异常，请联系管理员",null);
        }
    }
    /***
     * 聊天消息
     */
    @RequestMapping(value = "/msgList")
    @ResponseBody
    @LoginRequired(purview = "0")
    public String msgList ( @RequestParam(value = "chatid", required = false) Integer  chatid,
                            @RequestParam(value = "page"        , required = false, defaultValue = "1") Integer page,
                            @RequestParam(value = "token", required = false) String  token,
                            @RequestParam(value = "limit"       , required = false, defaultValue = "15") Integer limit) {
        if(chatid==null){
            return Result.getResultJson(0,"参数不正确",null);
        }
        Map map = redisHelp.getMapValue(this.dataprefix + "_" + "userInfo" + token, redisTemplate);
        Integer loguid =Integer.parseInt(map.get("uid").toString());

        if(limit>300){
            limit = 300;
        }

        TypechoChatMsg query = new TypechoChatMsg();
        query.setCid(chatid);
        List jsonList = new ArrayList();
        List cacheList = redisHelp.getList(this.dataprefix+"_"+"msgList_"+chatid+"_"+page+"_"+limit,redisTemplate);
        try{
            if(cacheList.size()>0){
                jsonList = cacheList;
            }else{
                TypechoApiconfig apiconfig = UStatus.getConfig(this.dataprefix,apiconfigService,redisTemplate);
                TypechoChat chat = service.selectByKey(chatid);
                if(chat==null){
                    return Result.getResultJson(0,"聊天室不存在",null);
                }
                PageList<TypechoChatMsg> pageList = chatMsgService.selectPage(query, page, limit);
                List<TypechoChatMsg> list = pageList.getList();
                if(list.size() < 1){
                    JSONObject noData = new JSONObject();
                    noData.put("code" , 1);
                    noData.put("msg"  , "");
                    noData.put("data" , new ArrayList());
                    noData.put("count", 0);
                    return noData.toString();
                }
                for (int i = 0; i < list.size(); i++) {
                    Map json = JSONObject.parseObject(JSONObject.toJSONString(list.get(i)), Map.class);
                    TypechoChatMsg msg = list.get(i);
                    Integer userid = msg.getUid();
                    TypechoUsers user = usersService.selectByKey(userid);
                    //获取用户信息
                    Map userJson = new HashMap();
                    if(user!=null){
                        String name = user.getName();
                        if(user.getScreenName()!=null){
                            name = user.getScreenName();
                        }
                        userJson.put("name", name);
                        userJson.put("groupKey", user.getGroupKey());
                        userJson.put("uid", user.getUid());
                        if(user.getAvatar()==null){
                            if(user.getMail()!=null){
                                String mail = user.getMail();

                                if(mail.indexOf("@qq.com") != -1){
                                    String qq = mail.replace("@qq.com","");
                                    userJson.put("avatar", "https://q1.qlogo.cn/g?b=qq&nk="+qq+"&s=640");
                                }else{
                                    userJson.put("avatar", baseFull.getAvatar(apiconfig.getWebinfoAvatar(), mail));
                                }
                                //json.put("avatar",baseFull.getAvatar(apiconfig.getWebinfoAvatar(),user.getMail()));
                            }else{
                                userJson.put("avatar",apiconfig.getWebinfoAvatar()+"null");
                            }
                        }else{
                            userJson.put("avatar", user.getAvatar());
                        }
                        userJson.put("customize", user.getCustomize());
                        userJson.put("introduce", user.getIntroduce());
                        //判断是否为VIP
                        userJson.put("vip", user.getVip());
                        userJson.put("isvip", 0);
                        Long date = System.currentTimeMillis();
                        String curTime = String.valueOf(date).substring(0, 10);
                        Integer viptime  = user.getVip();
                        if(viptime>Integer.parseInt(curTime)||viptime.equals(1)){
                            userJson.put("isvip", 1);
                        }

                    }else{
                        userJson.put("name", "用户已注销");
                        userJson.put("groupKey", "");
                        userJson.put("avatar", apiconfig.getWebinfoAvatar() + "null");
                    }
                    json.put("userJson",userJson);
                    //获取最新消息
                    jsonList.add(json);
                }

                redisHelp.delete(this.dataprefix+"_"+"msgList_"+chatid+"_"+page+"_"+limit,redisTemplate);
                redisHelp.setList(this.dataprefix+"_"+"msgList_"+chatid+"_"+page+"_"+limit,jsonList,120,redisTemplate);
            }
        }catch (Exception e){
            e.printStackTrace();
            if(cacheList.size()>0){
                jsonList = cacheList;
            }
        }
        JSONObject response = new JSONObject();
        response.put("code" , 1);
        response.put("msg"  , "");
        response.put("data" , jsonList);
        response.put("count", jsonList.size());
        return response.toString();
    }

    /**
     * 删除聊天室
     */
    @RequestMapping(value = "/deleteChat")
    @ResponseBody
    @LoginRequired(purview = "1")
    public String deleteChat (@RequestParam(value = "chatid", required = false) Integer  chatid,
                              @RequestParam(value = "token", required = false) String  token) {
        if(chatid==null){
            return Result.getResultJson(0,"参数不正确",null);
        }
        try {
            Map map = redisHelp.getMapValue(this.dataprefix + "_" + "userInfo" + token, redisTemplate);
            Integer logUid =Integer.parseInt(map.get("uid").toString());
            TypechoChat chat = service.selectByKey(chatid);
            if(chat==null){
                return Result.getResultJson(0,"聊天室不存在",null);
            }
            //删除聊天室全部消息
            chatMsgService.delete(chatid);
            //删除聊天室
            int rows = service.delete(chatid);
            editFile.setLog("管理员"+logUid+"请求删除（清空聊天室）："+chatid);
            JSONObject response = new JSONObject();
            response.put("code", rows > 0 ? 1 : 0);
            response.put("data", rows);
            response.put("msg", rows > 0 ? "操作成功" : "操作失败");
            return response.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return Result.getResultJson(0,"接口请求异常，请联系管理员",null);
        }

    }
    /**
     * 删除聊天消息
     */
    @RequestMapping(value = "/deleteMsg")
    @ResponseBody
    @LoginRequired(purview = "1")
    public String deleteMsg (@RequestParam(value = "msgid", required = false) Integer  msgid,
                             @RequestParam(value = "token", required = false) String  token) {
        if(msgid==null){
            return Result.getResultJson(0,"参数不正确",null);
        }
        try {
            Map map = redisHelp.getMapValue(this.dataprefix + "_" + "userInfo" + token, redisTemplate);
            Integer logUid =Integer.parseInt(map.get("uid").toString());
            TypechoChatMsg msg = chatMsgService.selectByKey(msgid);
            if(msg==null){
                return Result.getResultJson(0,"聊天消息不存在",null);
            }
            //删除消息
            int rows = chatMsgService.deleteMsg(msgid);
            editFile.setLog("管理员"+logUid+"请求删除聊天消息："+msgid);
            JSONObject response = new JSONObject();
            response.put("code", rows > 0 ? 1 : 0);
            response.put("data", rows);
            response.put("msg", rows > 0 ? "操作成功" : "操作失败");
            return response.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return Result.getResultJson(0,"接口请求异常，请联系管理员",null);
        }

    }

    /***
     * 管理员创建群聊
     */
    @RequestMapping(value = "/createGroup")
    @ResponseBody
    @LoginRequired(purview = "1")
    public String createChat(@RequestParam(value = "name", required = false) String  name,
                             @RequestParam(value = "pic", required = false) String  pic,
                             @RequestParam(value = "token", required = false) String  token) {

        if(name.length()<1||pic.length()<1){
            return Result.getResultJson(0,"必须设置群聊图片和名称",null);
        }
        try{
            Map map = redisHelp.getMapValue(this.dataprefix + "_" + "userInfo" + token, redisTemplate);
            Long date = System.currentTimeMillis();
            String created = String.valueOf(date).substring(0,10);
            Integer uid =Integer.parseInt(map.get("uid").toString());
            TypechoChat chat = new TypechoChat();
            chat.setName(name);
            chat.setPic(pic);
            chat.setUid(uid);
            chat.setType(1);
            chat.setCreated(Integer.parseInt(created));
            chat.setLastTime(Integer.parseInt(created));
            int rows = service.insert(chat);
            editFile.setLog("管理员"+uid+"请求创建聊天室");
            JSONObject response = new JSONObject();
            response.put("code", rows > 0 ? 1 : 0);
            response.put("data", rows);
            response.put("msg", rows > 0 ? "创建成功" : "创建失败");
            return response.toString();
        }catch (Exception e){
            e.printStackTrace();
            return Result.getResultJson(0,"接口请求异常，请联系管理员",null);
        }
    }
    /***
     * 管理员编辑群聊
     */
    @RequestMapping(value = "/editGroup")
    @ResponseBody
    @LoginRequired(purview = "1")
    public String editGroup(@RequestParam(value = "name", required = false) String  name,
                            @RequestParam(value = "id", required = false) Integer  id,
                            @RequestParam(value = "pic", required = false) String  pic,
                            @RequestParam(value = "token", required = false) String  token) {

        try{
            Map map = redisHelp.getMapValue(this.dataprefix + "_" + "userInfo" + token, redisTemplate);
            TypechoChat oldChat = service.selectByKey(id);
            if(oldChat == null){
                return Result.getResultJson(0,"群聊不存在",null);
            }
            Integer uid =Integer.parseInt(map.get("uid").toString());
            TypechoChat chat = new TypechoChat();
            chat.setId(id);
            chat.setName(name);
            chat.setPic(pic);

            int rows = service.update(chat);
            editFile.setLog("管理员"+uid+"请求修改聊天室");
            JSONObject response = new JSONObject();
            response.put("code", rows > 0 ? 1 : 0);
            response.put("data", rows);
            response.put("msg", rows > 0 ? "修改成功" : "修改失败");
            return response.toString();
        }catch (Exception e){
            e.printStackTrace();
            return Result.getResultJson(0,"接口请求异常，请联系管理员",null);
        }
    }
    /***
     * 屏蔽和全群禁言
     */
    @RequestMapping(value = "/banChat")
    @ResponseBody
    @LoginRequired(purview = "0")
    public String banChat(@RequestParam(value = "id", required = false) Integer  id,
                          @RequestParam(value = "token", required = false) String  token,
                          @RequestParam(value = "type", required = false, defaultValue = "1") Integer  type) {
        try {

            Map map = redisHelp.getMapValue(this.dataprefix + "_" + "userInfo" + token, redisTemplate);
            String group = map.get("group").toString();
            Integer uid =Integer.parseInt(map.get("uid").toString());
            TypechoChat oldChat = service.selectByKey(id);
            if(oldChat == null){
                return Result.getResultJson(0,"聊天室不存在",null);
            }
            if(oldChat.getType().equals(1)){
                if(!group.equals("administrator")&&!group.equals("editor")){
                    return Result.getResultJson(0,"你没有操作权限",null);
                }
//                if(!oldChat.getBan().equals(0)){
//                    return Result.getResultJson(0,"该群聊已被全体禁言",null);
//                }
            }else{
                if(oldChat.getUid().equals(uid)&&oldChat.getToid().equals(uid)){
                    return Result.getResultJson(0,"你没有操作权限",null);
                }
                if(!oldChat.getBan().equals(0)){
                    if(!oldChat.getBan().equals(uid)){
                        return Result.getResultJson(0,"你没有操作权限",null);
                    }

                }

            }
            TypechoChat chat = new TypechoChat();
            chat.setId(id);
            if(type.equals(1)){
                chat.setBan(uid);
            }else{
                chat.setBan(0);
            }

            int rows = service.update(chat);
            //发送系统消息
            Long date = System.currentTimeMillis();
            String created = String.valueOf(date).substring(0,10);
            TypechoChatMsg msgbox = new TypechoChatMsg();
            msgbox.setCid(id);
            msgbox.setUid(uid);
            if(type.equals(1)) {
                msgbox.setText("ban");
            }else{
                msgbox.setText("noban");
            }
            msgbox.setCreated(Integer.parseInt(created));
            msgbox.setType(4);
            chatMsgService.insert(msgbox);
            editFile.setLog("用户"+uid+"请求屏蔽or禁言聊天室");
            JSONObject response = new JSONObject();
            response.put("code", rows > 0 ? 1 : 0);
            response.put("data", rows);
            response.put("msg", rows > 0 ? "操作成功" : "操作失败");
            return response.toString();
        }catch (Exception e){
            e.printStackTrace();
            return Result.getResultJson(0,"接口请求异常，请联系管理员",null);
        }



    }
    /***
     * 群聊信息
     */
    @RequestMapping(value = "/groupInfo")
    @ResponseBody
    @LoginRequired(purview = "-1")
    public String groupInfo(@RequestParam(value = "id", required = false) Integer  id) {

        try{
            Map groupInfoJson = new HashMap<String, String>();
            Map cacheInfo = redisHelp.getMapValue(this.dataprefix+"_"+"groupInfoJson_"+id,redisTemplate);

            if(cacheInfo.size()>0){
                groupInfoJson = cacheInfo;
            }else{
                TypechoChat chat = service.selectByKey(id);
                if(chat == null){
                    return Result.getResultJson(0,"群聊不存在",null);
                }
                TypechoApiconfig apiconfig = UStatus.getConfig(this.dataprefix,apiconfigService,redisTemplate);
                //获取创建人信息
                Integer userid = chat.getUid();
                Map userJson = UserStatus.getUserInfo(userid,apiconfigService,usersService);
                groupInfoJson.put("userJson",userJson);
                groupInfoJson = JSONObject.parseObject(JSONObject.toJSONString(chat), Map.class);
                redisHelp.delete(this.dataprefix+"_"+"groupInfoJson_"+id,redisTemplate);
                redisHelp.setKey(this.dataprefix+"_"+"groupInfoJson_"+id,groupInfoJson,5,redisTemplate);
            }

            JSONObject response = new JSONObject();

            response.put("code", 1);
            response.put("msg", "");
            response.put("data", groupInfoJson);

            return response.toString();
        }catch (Exception e){
            e.printStackTrace();
            JSONObject response = new JSONObject();
            response.put("code", 1);
            response.put("msg", "");
            response.put("data", null);

            return response.toString();
        }
    }

    /***
     * 全部聊天
     */
    @RequestMapping(value = "/allChat")
    @ResponseBody
    @LoginRequired(purview = "0")
    public String allGroup (@RequestParam(value = "page"        , required = false, defaultValue = "1") Integer page,
                            @RequestParam(value = "order", required = false, defaultValue = "created") String  order,
                            @RequestParam(value = "type", required = false, defaultValue = "1") Integer  type,
                            @RequestParam(value = "limit"       , required = false, defaultValue = "15") Integer limit,
                            @RequestParam(value = "searchKey"        , required = false, defaultValue = "") String searchKey,
                            @RequestParam(value = "token", required = false) String  token) {
        if(limit>50){
            limit = 50;
        }
        TypechoChat query = new TypechoChat();
        query.setType(1);
        //管理员可以查看所有聊天，普通用户只能查看群聊
        Integer uStatus = UStatus.getStatus(token,this.dataprefix,redisTemplate);
        if(uStatus==0){
            query.setType(1);
            type = 1;
        }else{
            Map map = redisHelp.getMapValue(this.dataprefix + "_" + "userInfo" + token, redisTemplate);
            String group = map.get("group").toString();
            if(group.equals("administrator")||group.equals("editor")){
                query.setType(type);

            }

        }

        List jsonList = new ArrayList();
        List cacheList = redisHelp.getList(this.dataprefix+"_"+"allGroup_"+page+"_"+limit+"_"+type+"_"+order+"_"+searchKey,redisTemplate);
        Integer total = service.total(query);
        try{
            if(cacheList.size()>0){
                jsonList = cacheList;
            }else{
                TypechoApiconfig apiconfig = UStatus.getConfig(this.dataprefix,apiconfigService,redisTemplate);

                PageList<TypechoChat> pageList = service.selectPage(query, page, limit,order,searchKey);
                List<TypechoChat> list = pageList.getList();
                if(list.size() < 1){
                    JSONObject noData = new JSONObject();
                    noData.put("code" , 1);
                    noData.put("msg"  , "");
                    noData.put("data" , new ArrayList());
                    noData.put("count", 0);
                    noData.put("total", total);
                    return noData.toString();
                }
                for (int i = 0; i < list.size(); i++) {
                    Map json = JSONObject.parseObject(JSONObject.toJSONString(list.get(i)), Map.class);
                    TypechoChat chat = list.get(i);

                    //获取最新聊天消息
                    Integer chatid = chat.getId();
                    TypechoChatMsg msg = new TypechoChatMsg();
                    msg.setCid(chatid);
                    List<TypechoChatMsg> msgList = chatMsgService.selectList(msg);
                    if(msgList.size()>0) {
                        Integer msgUid = msgList.get(0).getUid();
                        TypechoUsers msgUser = usersService.selectByKey(msgUid);

                        Map lastMsg = JSONObject.parseObject(JSONObject.toJSONString(msgList.get(0)), Map.class);
                        if(msgUser!=null){
                            if(msgUser.getScreenName()!=null){
                                lastMsg.put("name",msgUser.getScreenName());
                            }else{
                                lastMsg.put("name",msgUser.getName());
                            }
                        }else{
                            lastMsg.put("name","用户已注销");
                        }

                        json.put("lastMsg",lastMsg);
                    }
                    Integer msgNum = chatMsgService.total(msg);
                    json.put("msgNum",msgNum);

                    if(type.equals(0)){
                        //获取聊天发起人信息
                        Integer userid = chat.getUid();
                        Integer toUserid = chat.getToid();
                        TypechoUsers user = usersService.selectByKey(userid);
                        TypechoUsers toUser = usersService.selectByKey(toUserid);
                        //获取用户信息
                        Map userJson = new HashMap();
                        if(user!=null){
                            String name = user.getName();
                            if(user.getScreenName()!=null){
                                name = user.getScreenName();
                            }
                            String toName = toUser.getName();
                            if(toUser.getScreenName()!=null){
                                toName = toUser.getScreenName();
                            }
                            userJson.put("name", name);
                            userJson.put("toName", toName);
                            userJson.put("groupKey", user.getGroupKey());

                            if(user.getAvatar()==null){
                                if(user.getMail()!=null){
                                    String mail = user.getMail();
                                    if(mail.indexOf("@qq.com") != -1){
                                        String qq = mail.replace("@qq.com","");
                                        userJson.put("avatar", "https://q1.qlogo.cn/g?b=qq&nk="+qq+"&s=640");
                                    }else{
                                        userJson.put("avatar", baseFull.getAvatar(apiconfig.getWebinfoAvatar(), mail));
                                    }
                                    //json.put("avatar",baseFull.getAvatar(apiconfig.getWebinfoAvatar(),user.getMail()));
                                }else{
                                    userJson.put("avatar",apiconfig.getWebinfoAvatar()+"null");
                                }
                            }else{
                                userJson.put("avatar", user.getAvatar());
                            }
                            userJson.put("uid", user.getUid());
                            userJson.put("touid", toUser.getUid());
                            userJson.put("customize", user.getCustomize());
                            userJson.put("experience",user.getExperience());
                            userJson.put("introduce", user.getIntroduce());
                            //判断是否为VIP
                            userJson.put("vip", user.getVip());
                            userJson.put("isvip", 0);
                            Long date = System.currentTimeMillis();
                            String curTime = String.valueOf(date).substring(0, 10);
                            Integer viptime  = user.getVip();
                            if(viptime>Integer.parseInt(curTime)||viptime.equals(1)){
                                userJson.put("isvip", 1);
                            }

                        }else{
                            userJson.put("name", "用户已注销");
                            userJson.put("groupKey", "");
                            userJson.put("avatar", apiconfig.getWebinfoAvatar() + "null");
                        }
                        json.put("userJson",userJson);
                    }

                    jsonList.add(json);
                }
                redisHelp.delete(this.dataprefix+"_"+"allGroup_"+page+"_"+limit+"_"+type+"_"+order+"_"+searchKey,redisTemplate);
                redisHelp.setList(this.dataprefix+"_"+"allGroup_"+page+"_"+limit+"_"+type+"_"+order+"_"+searchKey,jsonList,5,redisTemplate);
            }
        }catch (Exception e){
            e.printStackTrace();
            if(cacheList.size()>0){
                jsonList = cacheList;
            }
        }
        JSONObject response = new JSONObject();
        response.put("code" , 1);
        response.put("msg"  , "");
        response.put("data" , jsonList);
        response.put("count", jsonList.size());
        response.put("total", total);
        return response.toString();

    }



}
