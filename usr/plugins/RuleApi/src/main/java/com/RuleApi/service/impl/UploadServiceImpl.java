package com.RuleApi.service.impl;

import com.RuleApi.common.*;
import com.RuleApi.entity.TypechoApiconfig;
import com.RuleApi.service.UploadService;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.google.gson.Gson;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.region.Region;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.DefaultPutRet;
import com.qiniu.util.Auth;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

@Service
public class UploadServiceImpl  implements UploadService {

    ResultAll Result = new ResultAll();
    baseFull baseFull = new baseFull();
    EditFile editFile = new EditFile();

    public String base64Upload(String base64Img, String dataprefix, TypechoApiconfig apiconfig, Integer uid) {
        try {
            // 假设从dataprefix中解析文件扩展名
            String eName = ".png"; // 示例扩展名, 根据实际情况调整

            String base64Image = base64Img.split(",")[1];
            byte[] imageBytes = Base64.getDecoder().decode(base64Image);
            InputStream inputStream = new ByteArrayInputStream(imageBytes);

            String newFileName = UUID.randomUUID() + eName;
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            if(apiconfig.getUploadType().equals("cos")){
                COSCredentials cred = new BasicCOSCredentials(apiconfig.getCosAccessKey(), apiconfig.getCosSecretKey());
                ClientConfig clientConfig = new ClientConfig(new Region(apiconfig.getCosBucket()));
                COSClient cosclient = new COSClient(cred, clientConfig);

                String bucketName = apiconfig.getCosBucketName();
                String key = "/" + apiconfig.getCosPrefix() + "/" + year + "/" + month + "/" + day + "/" + newFileName;

                try {
                    PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, inputStream, null);
                    PutObjectResult putObjectResult = cosclient.putObject(putObjectRequest);
                    // 假设editFile.setLog是有效的日志记录方法
                    editFile.setLog("用户" + uid + "通过cosUpload成功上传了图片");
                    return apiconfig.getCosPath() + putObjectRequest.getKey();
                } finally {
                    cosclient.shutdown();
                    if(inputStream != null) {
                        inputStream.close();
                    }
                }
            }
            if(apiconfig.getUploadType().equals("local")){
                String decodeClassespath = null;
                //如果用户自定义了本地存储地址，则使用自定义地址，否则调用jar所在路径拼接地址
                if(apiconfig.getLocalPath()!=null&&!apiconfig.getLocalPath().isEmpty()){
                    decodeClassespath = apiconfig.getLocalPath();
                }else{
                    /*解决文件路径中的空格问题*/
                    ApplicationHome h = new ApplicationHome(getClass());
                    File jarF = h.getSource();
                    /* 配置文件路径 */
                    String classespath = jarF.getParentFile().toString()+"/files/static";
                    try {
                        decodeClassespath = URLDecoder.decode(classespath,"utf-8");
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                        decodeClassespath = "/opt/files/static";
                    }
                }
                File file1 = new File(decodeClassespath+"/upload/"+"/"+year+"/"+month+"/"+day+"/"+newFileName);
                if(!file1.exists()){
                    file1.mkdirs();
                }
                try {
                    Files.copy(inputStream, file1.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    Map<String,String> info =new HashMap<String, String>();
                    info.put("url",apiconfig.getWebinfoUploadUrl()+"upload"+"/"+year+"/"+month+"/"+day+"/"+newFileName);
                    editFile.setLog("用户"+uid+"通过localUpload成功上传了图片");
                    return Result.getResultJson(1,"上传成功",info);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                editFile.setLog("用户"+uid+"通过localUpload上传图片失败");
                return Result.getResultJson(0,"上传失败",null);
            }
            if(apiconfig.getUploadType().equals("local")){
                OSS ossClient = new OSSClientBuilder().build(apiconfig.getAliyunEndpoint(), apiconfig.getAliyunAccessKeyId(),apiconfig.getAliyunAccessKeySecret());
                String key = apiconfig.getAliyunFilePrefix()+"/"+year+"/"+month+"/"+day+"/"+newFileName;
                //调用OSS方法实现上传
                ossClient.putObject(apiconfig.getAliyunAucketName(), key, inputStream);
                ossClient.shutdown();
                String url = apiconfig.getAliyunUrlPrefix()+key;
                Map<String,String> info =new HashMap<String, String>();
                info.put("url",url);
                editFile.setLog("用户"+uid+"通过ossUpload成功上传了图片");
                return Result.getResultJson(1,"上传成功",info);
            }
            if(apiconfig.getUploadType().equals("qiniu")){
                String key = "/app/"+year+"/"+month+"/"+day+"/"+newFileName;

                // 构造一个带指定Zone对象的配置类, 注意这里的Zone.zone0需要根据主机选择

                Configuration cfg = new Configuration();
                // 其他参数参考类注释
                UploadManager uploadManager = new UploadManager(cfg);
                // 生成上传凭证，然后准备上传

                try {
                    Auth auth = Auth.create(apiconfig.getQiniuAccessKey(), apiconfig.getQiniuSecretKey());
                    String upToken = auth.uploadToken(apiconfig.getQiniuBucketName());
                    try {
                        Response response = uploadManager.put(inputStream, key, upToken, null, null);
                        // 解析上传成功的结果
                        DefaultPutRet putRet = new Gson().fromJson(response.bodyString(), DefaultPutRet.class);

                        String returnPath = apiconfig.getQiniuDomain() + putRet.key;
                        // 这个returnPath是获得到的外链地址,通过这个地址可以直接打开图片
                        Map<String,String> info =new HashMap<String, String>();
                        info.put("url",returnPath);
                        editFile.setLog("用户"+uid+"通过qiniuUpload成功上传了图片");
                        return Result.getResultJson(1,"上传成功",info);
                    } catch (QiniuException ex) {
                        Response r = ex.response;
                        System.err.println(r.toString());
                        try {
                            System.err.println(r.bodyString());
                        } catch (QiniuException ex2) {
                            //ignore
                        }
                        return Result.getResultJson(1,"上传失败",null);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return Result.getResultJson(1,"上传失败",null);
                }
            }
            if(apiconfig.getUploadType().equals("ftp")){
                //指定存放上传文件的目录
                ApplicationHome h = new ApplicationHome(getClass());
                File jarF = h.getSource();
                /* 配置文件路径 */
                String classespath = jarF.getParentFile().toString()+"/files";

                String decodeClassespath = URLDecoder.decode(classespath,"utf-8");
                String fileDir = decodeClassespath+"/temp";
                File dir = new File(fileDir);

                //判断目录是否存在，不存在则创建目录
                if (!dir.exists()){
                    dir.mkdirs();
                }
                //生成文件
                File file1 = new File(dir, newFileName);
                //传输内容
                try {
                    Files.copy(inputStream, file1.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("上传文件成功！");
                } catch (IOException e) {
                    System.out.println("上传文件失败！");
                    e.printStackTrace();
                }
                //在服务器上生成新的目录
                FTPClient ftpClient = new FTPClient();
                String key = apiconfig.getFtpBasePath()+"/"+file1.getName();

                ftpClient.setConnectTimeout(1000 * 30);//设置连接超时时间
                ftpClient.setControlEncoding("utf-8");//设置ftp字符集
                //连接ftp服务器 参数填服务器的ip
                ftpClient.connect(apiconfig.getFtpHost(),apiconfig.getFtpPort());

                //进行登录 参数分别为账号 密码
                ftpClient.login(apiconfig.getFtpUsername(),apiconfig.getFtpPassword());
                //开启被动模式（按自己如何配置的ftp服务器来决定是否开启）
                ftpClient.enterLocalPassiveMode();
                //只能选择local_root下已存在的目录
                //ftpClient.changeWorkingDirectory(this.ftpBasePath);

                // 文件夹不存在时新建
                String remotePath = apiconfig.getFtpBasePath();
                if (!ftpClient.changeWorkingDirectory(remotePath)) {
                    ftpClient.makeDirectory(remotePath);
                    ftpClient.changeWorkingDirectory(remotePath);
                }
                //设置文件类型为二进制文件
                ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
                //inputStream = file.getInputStream();
                //上传文件 参数：上传后的文件名，输入流
                ftpClient.storeFile(key, new FileInputStream(file1));

                ftpClient.disconnect();
                Map<String,String> info =new HashMap<String, String>();
                info.put("url",apiconfig.getWebinfoUploadUrl()+key);
                editFile.setLog("用户"+uid+"通过ftpUpload成功上传了图片");
                return Result.getResultJson(1,"上传成功",info);
            }

            editFile.setLog("用户" + uid + "上传图片失败");
            return "";

        } catch (Exception e) {
            e.printStackTrace();
            editFile.setLog("用户" + uid + "上传图片失败");
            return "";
        }
    }

    public String cosUpload(MultipartFile file, String  dataprefix, TypechoApiconfig apiconfig,Integer uid){

        String oldFileName = file.getOriginalFilename();
        //String eName = oldFileName.substring(oldFileName.lastIndexOf("."));
        String eName = "";
        try{
            eName = oldFileName.substring(oldFileName.lastIndexOf("."));
        }catch (Exception e){
            oldFileName = oldFileName +".png";
            eName = oldFileName.substring(oldFileName.lastIndexOf("."));
        }

        //根据权限等级检查是否为图片
        Integer uploadLevel = apiconfig.getUploadLevel();
        if(uploadLevel.equals(1)){
            return Result.getResultJson(0,"管理员已关闭上传功能",null);
        }
        if(uploadLevel.equals(0)){
            //检查是否是图片
            BufferedImage bi = null;
            try {
                bi = ImageIO.read(file.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            if(bi == null&&!eName.equals(".WEBP")&&!eName.equals(".webp")){
                return Result.getResultJson(0,"当前只允许上传图片文件",null);
            }
        }
        if(uploadLevel.equals(2)){
            //检查是否是图片或视频
            BufferedImage bi = null;
            try {
                bi = ImageIO.read(file.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            Integer isVideo = baseFull.isVideo(eName);
            if(bi == null&&!eName.equals(".WEBP")&&!eName.equals(".webp")&&!isVideo.equals(1)){
                return Result.getResultJson(0,"请上传图片或者视频文件",null);
            }
        }

        String newFileName = UUID.randomUUID()+eName;
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month=cal.get(Calendar.MONTH)+1;
        int day=cal.get(Calendar.DATE);
        // 1 初始化用户身份信息(secretId, secretKey)
        COSCredentials cred = new BasicCOSCredentials(apiconfig.getCosAccessKey(), apiconfig.getCosSecretKey());
        // 2 设置bucket的区域, COS地域的简称请参照 https://cloud.tencent.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region(apiconfig.getCosBucket()));
        // 3 生成cos客户端
        COSClient cosclient = new COSClient(cred, clientConfig);
        // bucket的命名规则为{name}-{appid} ，此处填写的存储桶名称必须为此格式
        String bucketName = apiconfig.getCosBucketName();

        // 简单文件上传, 最大支持 5 GB, 适用于小文件上传, 建议 20 M 以下的文件使用该接口
        // 大文件上传请参照 API 文档高级 API 上传
        File localFile = null;
        try {
            localFile = File.createTempFile("temp",null);
            file.transferTo(localFile);
            // 指定要上传到 COS 上的路径
            String key = "/"+apiconfig.getCosPrefix()+"/"+year+"/"+month+"/"+day+"/"+newFileName;
            PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, key, localFile);
            PutObjectResult putObjectResult = cosclient.putObject(putObjectRequest);
            //return new UploadMsg(1,"上传成功",this.path + putObjectRequest.getKey());
            Map<String,String> info =new HashMap<String, String>();
            info.put("url",apiconfig.getCosPath() + putObjectRequest.getKey());
            editFile.setLog("用户"+uid+"通过cosUpload成功上传了图片");
            return Result.getResultJson(1,"上传成功",info);

        } catch (IOException e) {
            editFile.setLog("用户"+uid+"通过cosUpload上传图片失败");
            return Result.getResultJson(0,"上传失败",null);
        }finally {
            // 关闭客户端(关闭后台线程)
            cosclient.shutdown();
        }
    }
    public String localUpload(MultipartFile file, String  dataprefix,TypechoApiconfig apiconfig,Integer uid){
        String filename = file.getOriginalFilename();
        //String filetype = filename.substring(filename.lastIndexOf("."));
        //下面代码是解决app上传剪裁后图片无后缀问题。
        String filetype = "";
        try{
            filetype = filename.substring(filename.lastIndexOf("."));
        }catch (Exception e){
            filename = filename +".png";
            filetype = filename.substring(filename.lastIndexOf("."));
        }
        String newfile = UUID.randomUUID()+filetype;
        //根据权限等级检查是否为图片
        Integer uploadLevel = apiconfig.getUploadLevel();
        if(uploadLevel.equals(1)){
            return Result.getResultJson(0,"管理员已关闭上传功能",null);
        }
        if(uploadLevel.equals(0)){
            //检查是否是图片
            BufferedImage bi = null;
            try {
                bi = ImageIO.read(file.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            if(bi == null&&!filetype.equals(".WEBP")&&!filetype.equals(".webp")){
                return Result.getResultJson(0,"当前只允许上传图片文件",null);
            }
        }
        if(uploadLevel.equals(2)){
            //检查是否是图片或视频
            BufferedImage bi = null;
            try {
                bi = ImageIO.read(file.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            Integer isVideo = baseFull.isVideo(filetype);
            if(bi == null&&!filetype.equals(".WEBP")&&!filetype.equals(".webp")&&!isVideo.equals(1)){
                return Result.getResultJson(0,"请上传图片或者视频文件",null);
            }
        }



        String decodeClassespath = null;
        //如果用户自定义了本地存储地址，则使用自定义地址，否则调用jar所在路径拼接地址
        if(apiconfig.getLocalPath()!=null&&!apiconfig.getLocalPath().isEmpty()){
            decodeClassespath = apiconfig.getLocalPath();
        }else{
            /*解决文件路径中的空格问题*/
            ApplicationHome h = new ApplicationHome(getClass());
            File jarF = h.getSource();
            /* 配置文件路径 */
            String classespath = jarF.getParentFile().toString()+"/files/static";
            try {
                decodeClassespath = URLDecoder.decode(classespath,"utf-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                decodeClassespath = "/opt/files/static";
            }
        }
        //System.out.println(decodeClassespath);

        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month=cal.get(Calendar.MONTH)+1;
        int day=cal.get(Calendar.DATE);

        /**/
        File file1 = new File(decodeClassespath+"/upload/"+"/"+year+"/"+month+"/"+day+"/"+newfile);
        if(!file1.exists()){
            file1.mkdirs();
        }
        try {
            file.transferTo(file1);

            Map<String,String> info =new HashMap<String, String>();
            info.put("url",apiconfig.getWebinfoUploadUrl()+"upload"+"/"+year+"/"+month+"/"+day+"/"+newfile);
            editFile.setLog("用户"+uid+"通过localUpload成功上传了图片");
            return Result.getResultJson(1,"上传成功",info);
        } catch (IOException e) {
            e.printStackTrace();
        }
        editFile.setLog("用户"+uid+"通过localUpload上传图片失败");
        return Result.getResultJson(0,"上传失败",null);
    }
    public String ossUpload(MultipartFile file, String  dataprefix,TypechoApiconfig apiconfig,Integer uid){
        //获取上传文件MultipartFile
        //返回上传到oss的路径
        OSS ossClient = new OSSClientBuilder().build(apiconfig.getAliyunEndpoint(), apiconfig.getAliyunAccessKeyId(),apiconfig.getAliyunAccessKeySecret());
        InputStream inputStream = null;
        try {
            //获取文件流
            inputStream = file.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //生成时间，用于创建目录
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month=cal.get(Calendar.MONTH)+1;
        int day=cal.get(Calendar.DATE);
        //获取文件名称
        String filename = file.getOriginalFilename();
        //String eName = filename.substring(filename.lastIndexOf("."));
        //应对图片剪裁后的无后缀图片
        String eName = "";
        try{
            eName = filename.substring(filename.lastIndexOf("."));
        }catch (Exception e){
            filename = filename +".png";
            eName = filename.substring(filename.lastIndexOf("."));
        }
        //根据权限等级检查是否为图片
        Integer uploadLevel = apiconfig.getUploadLevel();
        if(uploadLevel.equals(1)){
            return Result.getResultJson(0,"管理员已关闭上传功能",null);
        }
        if(uploadLevel.equals(0)){
            //检查是否是图片或视频
            BufferedImage bi = null;
            try {
                bi = ImageIO.read(file.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            if(bi == null&&!eName.equals(".WEBP")&&!eName.equals(".webp")){
                return Result.getResultJson(0,"当前只允许上传图片文件",null);
            }
        }
        if(uploadLevel.equals(2)){
            //检查是否是图片
            BufferedImage bi = null;
            try {
                bi = ImageIO.read(file.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            Integer isVideo = baseFull.isVideo(eName);
            if(bi == null&&!eName.equals(".WEBP")&&!eName.equals(".webp")&&!isVideo.equals(1)){
                return Result.getResultJson(0,"请上传图片或者视频文件",null);
            }
        }
        //1.在文件名称中添加随机唯一的值
        String newFileName = UUID.randomUUID()+eName;

        // String uuid = UUID.randomUUID().toString().replaceAll("-","");
        filename = newFileName;

        String key = apiconfig.getAliyunFilePrefix()+"/"+year+"/"+month+"/"+day+"/"+filename;
        //调用OSS方法实现上传
        ossClient.putObject(apiconfig.getAliyunAucketName(), key, inputStream);
        ossClient.shutdown();
        String url = apiconfig.getAliyunUrlPrefix()+key;
        Map<String,String> info =new HashMap<String, String>();
        info.put("url",url);
        editFile.setLog("用户"+uid+"通过ossUpload成功上传了图片");
        return Result.getResultJson(1,"上传成功",info);
    }
    public String qiniuUpload(MultipartFile file, String  dataprefix,TypechoApiconfig apiconfig,Integer uid){
        //获取上传文件MultipartFile
        InputStream inputStream = null;

        try {
            //获取文件流
            inputStream = file.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
        //生成时间，用于创建目录
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        int month=cal.get(Calendar.MONTH)+1;
        int day=cal.get(Calendar.DATE);
        //获取文件名称
        String filename = file.getOriginalFilename();
        //String eName = filename.substring(filename.lastIndexOf("."));
        //应对图片剪裁后的无后缀图片
        String eName = "";
        try{
            eName = filename.substring(filename.lastIndexOf("."));
        }catch (Exception e){
            filename = filename +".png";
            eName = filename.substring(filename.lastIndexOf("."));
        }
        //根据权限等级检查是否为图片
        Integer uploadLevel = apiconfig.getUploadLevel();
        if(uploadLevel.equals(1)){
            return Result.getResultJson(0,"管理员已关闭上传功能",null);
        }
        if(uploadLevel.equals(0)){
            //检查是否是图片或视频
            BufferedImage bi = null;
            try {
                bi = ImageIO.read(file.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            if(bi == null&&!eName.equals(".WEBP")&&!eName.equals(".webp")){
                return Result.getResultJson(0,"当前只允许上传图片文件",null);
            }
        }
        if(uploadLevel.equals(2)){
            //检查是否是图片
            BufferedImage bi = null;
            try {
                bi = ImageIO.read(file.getInputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            Integer isVideo = baseFull.isVideo(eName);
            if(bi == null&&!eName.equals(".WEBP")&&!eName.equals(".webp")&&!isVideo.equals(1)){
                return Result.getResultJson(0,"请上传图片或者视频文件",null);
            }
        }
        //1.在文件名称中添加随机唯一的值
        String newFileName = UUID.randomUUID()+eName;

        // String uuid = UUID.randomUUID().toString().replaceAll("-","");
        filename = newFileName;

        String key = "/app/"+year+"/"+month+"/"+day+"/"+filename;

        // 构造一个带指定Zone对象的配置类, 注意这里的Zone.zone0需要根据主机选择

        Configuration cfg = new Configuration();
        // 其他参数参考类注释
        UploadManager uploadManager = new UploadManager(cfg);
        // 生成上传凭证，然后准备上传

        try {
            Auth auth = Auth.create(apiconfig.getQiniuAccessKey(), apiconfig.getQiniuSecretKey());
            String upToken = auth.uploadToken(apiconfig.getQiniuBucketName());
            FileInputStream fileInputStream = (FileInputStream) file.getInputStream();
            try {
                Response response = uploadManager.put(fileInputStream, key, upToken, null, null);
                // 解析上传成功的结果
                DefaultPutRet putRet = new Gson().fromJson(response.bodyString(), DefaultPutRet.class);

                String returnPath = apiconfig.getQiniuDomain() + putRet.key;
                // 这个returnPath是获得到的外链地址,通过这个地址可以直接打开图片
                Map<String,String> info =new HashMap<String, String>();
                info.put("url",returnPath);
                editFile.setLog("用户"+uid+"通过qiniuUpload成功上传了图片");
                return Result.getResultJson(1,"上传成功",info);
            } catch (QiniuException ex) {
                Response r = ex.response;
                System.err.println(r.toString());
                try {
                    System.err.println(r.bodyString());
                } catch (QiniuException ex2) {
                    //ignore
                }
                return Result.getResultJson(1,"上传失败",null);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return Result.getResultJson(1,"上传失败",null);
        }
    }
    public String ftpUpload(MultipartFile file, String  dataprefix,TypechoApiconfig apiconfig,Integer uid){
        FTPClient ftpClient = new FTPClient();
        try {

            //指定存放上传文件的目录
            ApplicationHome h = new ApplicationHome(getClass());
            File jarF = h.getSource();
            /* 配置文件路径 */
            String classespath = jarF.getParentFile().toString()+"/files";

            String decodeClassespath = URLDecoder.decode(classespath,"utf-8");
            String fileDir = decodeClassespath+"/temp";
            File dir = new File(fileDir);

            //判断目录是否存在，不存在则创建目录
            if (!dir.exists()){
                dir.mkdirs();
            }

            //生成新文件名，防止文件名重复而导致文件覆盖
            //1、获取原文件后缀名 .img .jpg ....
            String originalFileName = file.getOriginalFilename();
            //String suffix = originalFileName.substring(originalFileName.lastIndexOf('.'));
            //应对图片剪裁后的无后缀图片
            String suffix = "";
            try{
                suffix = originalFileName.substring(originalFileName.lastIndexOf("."));
            }catch (Exception e){
                originalFileName = originalFileName +".png";
                suffix = originalFileName.substring(originalFileName.lastIndexOf("."));
            }
            //根据权限等级检查是否为图片
            Integer uploadLevel = apiconfig.getUploadLevel();
            if(uploadLevel.equals(0)){
                //检查是否是图片
                BufferedImage bi = ImageIO.read(file.getInputStream());
                if(bi == null&&!suffix.equals(".WEBP")&&!suffix.equals(".webp")){
                    return Result.getResultJson(0,"当前只允许上传图片文件",null);
                }
            }
            if(uploadLevel.equals(2)){
                //检查是否是图片或视频
                BufferedImage bi = ImageIO.read(file.getInputStream());
                Integer isVideo = baseFull.isVideo(suffix);
                if(bi == null&&!suffix.equals(".WEBP")&&!suffix.equals(".webp")&&!isVideo.equals(1)){
                    return Result.getResultJson(0,"请上传图片或者视频文件",null);
                }
            }
            //2、使用UUID生成新文件名
            String newFileName = UUID.randomUUID() + suffix;
            //生成文件
            File file1 = new File(dir, newFileName);
            //传输内容
            try {
                file.transferTo(file1);
                System.out.println("上传文件成功！");
            } catch (IOException e) {
                System.out.println("上传文件失败！");
                e.printStackTrace();
            }

            //在服务器上生成新的目录
            String key = apiconfig.getFtpBasePath()+"/"+file1.getName();

            ftpClient.setConnectTimeout(1000 * 30);//设置连接超时时间
            ftpClient.setControlEncoding("utf-8");//设置ftp字符集
            //连接ftp服务器 参数填服务器的ip
            ftpClient.connect(apiconfig.getFtpHost(),apiconfig.getFtpPort());

            //进行登录 参数分别为账号 密码
            ftpClient.login(apiconfig.getFtpUsername(),apiconfig.getFtpPassword());
            //开启被动模式（按自己如何配置的ftp服务器来决定是否开启）
            ftpClient.enterLocalPassiveMode();
            //只能选择local_root下已存在的目录
            //ftpClient.changeWorkingDirectory(this.ftpBasePath);

            // 文件夹不存在时新建
            String remotePath = apiconfig.getFtpBasePath();
            if (!ftpClient.changeWorkingDirectory(remotePath)) {
                ftpClient.makeDirectory(remotePath);
                ftpClient.changeWorkingDirectory(remotePath);
            }
            //设置文件类型为二进制文件
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            //inputStream = file.getInputStream();
            //上传文件 参数：上传后的文件名，输入流
            ftpClient.storeFile(key, new FileInputStream(file1));

            ftpClient.disconnect();
            Map<String,String> info =new HashMap<String, String>();
            info.put("url",apiconfig.getWebinfoUploadUrl()+key);
            editFile.setLog("用户"+uid+"通过ftpUpload成功上传了图片");
            return Result.getResultJson(1,"上传成功",info);

        } catch (Exception e) {
            e.printStackTrace();
            editFile.setLog("用户"+uid+"通过ftpUpload上传图片失败");
            return Result.getResultJson(0,"上传失败",null);
        }
    }
}
