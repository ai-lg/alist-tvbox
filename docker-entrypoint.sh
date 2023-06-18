#!/bin/sh

java -jar alist-tvbox.jar --spring.profiles.active=production,xiaoya &

rm -f /tmp/updated

/updateall
/bin/busybox-extras httpd -p 81 -h /www
/usr/sbin/nginx

if [[ -f /data/pikpak.txt ]] && [[ -s /data/pikpak.txt ]]; then
  /pikpak
  echo $(date) "User's own PikPak account has been updated into database successfully"
fi

if [[ -s /data/mytoken.txt ]] && [[ -s /data/myopentoken.txt ]] && [[ -s /data/temp_transfer_folder_id.txt ]]; then
  user_open_token=$(head -n1 /data/myopentoken.txt)
  user_token=$(head -n1 /data/mytoken.txt)
  tempfolderid=$(head -n1 /data/temp_transfer_folder_id.txt)
  sqlite3 /opt/alist/data/data.db <<EOF
update x_storages set driver = "AliyundriveShare2Open" where driver = 'AliyundriveShare';
update x_storages set addition = json_set(addition, '$.RefreshToken', "$user_token") where driver = 'AliyundriveShare2Open';
update x_storages set addition = json_set(addition, '$.RefreshTokenOpen', "$user_open_token") where driver = 'AliyundriveShare2Open';
update x_storages set addition = json_set(addition, '$.TempTransferFolderID', "$tempfolderid") where driver = 'AliyundriveShare2Open';
EOF
  echo "driver updated to AliyundriveShare2Open"
  date >/tmp/updated
else
  echo "请检查已正确配置 mytoken.txt myopentoken.txt temp_transfer_folder_id.txt 后再重启容器"
  exit
fi

version=$(head -n1 /docker.version)
sqlite3 /opt/alist/data/data.db <<EOF
INSERT INTO x_storages VALUES(20000,'/©️ $version',0,'AList V3',30,'work','{"root_folder_path":"/安装，配置，修复 xiaoya docker 指南/打赏码，谢谢你的支持.jpg","url":"http://alist.xiaoya.pro","password":"","access_token":""}','','2022-11-12 13:05:12.467024193+00:00',0,'','','',0,'302_redirect','');
EOF

if [[ -f /data/proxy.txt ]] && [[ -s /data/proxy.txt ]]; then
  proxy_url=$(head -n1 /data/proxy.txt)
  export HTTP_PROXY=$proxy_url
  export HTTPS_PROXY=$proxy_url
  export no_proxy=*.aliyundrive.com
fi

cd /opt/alist

exec "$@"