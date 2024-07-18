# RuleApi

RuleApi，基于typecho正式版数据库，使用JAVA语言Springboot框架，整合redis缓存数据库，COS、OSS对象存储，是目前typecho程序功能最全，接口最完善，用户体验最好，且性能最佳的API程序。集成了用户模块（登陆，注册，邮箱验证，用户查询，用户修改），文章模块，评论模块，分类模块，和上传模块（三合一上传方式，OSS，COS，本地上传均可），在安装完成后，可以进一步扩展typecho网站的功能，并实现更强大的性能，更全面的应用范围。

目前已经兼容typecho1.2最新版。

接口文档地址：https://docs.apipost.cn/preview/12e2d0e7ab2f8738/9c7fd18771884cb2

## 安装教程

[RuleProject全项目安装教程](https://www.yuque.com/buxia97/ruleproject)

## 更新教程

https://www.ruletree.club/archives/2824/

## 功能明细

1.用户模块：包括登录，注册，找回密码，基于邮箱进行验证。拥有包括头衔体系，等级体系，VIP会员体系并配合支付模块，文章模块，商品模块，实现会员的充值提现，写作投稿，商品发布。

2.内容模块：包括随机，根据字段排行，推荐机制，redis缓存机制（文章，评论，标签分类），投稿邮件提醒机制在内的基础功能集成。同时支持文章挂载商品相互结合实现付费阅读。

3.商品模块：支持四种规范的商品发布，拥有完善的卖家和买家体系，实现卖出订单，买入订单等功能。并且商品可以根据类型挂载进文章。

4.支付模块：集成了支付宝当面付，微信APP支付，卡密充值等三种支付方式，并整合会员的积分系统，实现全站的收费体系，后续更多支付方式正在开发中。

5.其它功能：签到送积分，文章收藏，文章打赏，提现和提现审核机制，简易后台管理中心。可视化的接口端配置。

## 演示地址

https://www.ruletree.club/archives/2649/

## 相关应用

[Typecho独立会员中心，前后端分离，充值付费功能集成，APP扫码登录](https://www.ruletree.club/archives/2979/)

[RuleApp文章博客，VIP会员体系，写作投稿积分商城，多支付支持，多平台兼容](https://ext.dcloud.net.cn/plugin?id=6909)

## 协议及申明

[许可协议 / 免责声明](https://www.yuque.com/buxia97/ruleproject/gm1pzr6h0e1eqvvc)