<?php
// site root path
define('__TYPECHO_ROOT_DIR__', dirname(__FILE__));

// plugin directory (relative path)
define('__TYPECHO_PLUGIN_DIR__', '/usr/plugins');

// theme directory (relative path)
define('__TYPECHO_THEME_DIR__', '/usr/themes');

// admin directory (relative path)
define('__TYPECHO_ADMIN_DIR__', '/admin/');

// register autoload
require_once __TYPECHO_ROOT_DIR__ . '/var/Typecho/Common.php';

// init
\Typecho\Common::init();

// config db
$db = new \Typecho\Db('Pdo_Mysql', 'typecho1_');
$db->addServer(array (
  'host' => 'mysql7.serv00.com',
  'port' => 3306,
  'user' => 'm1098_admin01',
  'password' => 'Wh20001218',
  'charset' => 'utf8mb4',
  'database' => 'm1098_ruleapp',
  'engine' => 'InnoDB',
  'sslCa' => '',
  'sslVerify' => false,
), \Typecho\Db::READ | \Typecho\Db::WRITE);
\Typecho\Db::set($db);
