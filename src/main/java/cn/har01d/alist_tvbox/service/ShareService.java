package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.OpenApiDto;
import cn.har01d.alist_tvbox.dto.SharesDto;
import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.LoginRequest;
import cn.har01d.alist_tvbox.model.LoginResponse;
import cn.har01d.alist_tvbox.model.Response;
import cn.har01d.alist_tvbox.model.SettingResponse;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.ALI_SECRET;
import static cn.har01d.alist_tvbox.util.Constants.ATV_PASSWORD;
import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN_URL;

@Slf4j
@Service

public class ShareService {

    private final ObjectMapper objectMapper;
    private final ShareRepository shareRepository;
    private final AListAliasRepository aliasRepository;
    private final SettingRepository settingRepository;
    private final SiteRepository siteRepository;
    private final AccountRepository accountRepository;
    private final PikPakAccountRepository pikPakAccountRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;
    private final ConfigFileService configFileService;
    private final PikPakService pikPakService;
    private final RestTemplate restTemplate;
    private final RestTemplate restTemplate1;
    private final Environment environment;

    private volatile int shareId = 5000;

    public ShareService(ObjectMapper objectMapper,
                        ShareRepository shareRepository,
                        AListAliasRepository aliasRepository,
                        SettingRepository settingRepository,
                        SiteRepository siteRepository,
                        AccountRepository accountRepository,
                        PikPakAccountRepository pikPakAccountRepository,
                        AppProperties appProperties,
                        AccountService accountService,
                        AListLocalService aListLocalService,
                        ConfigFileService configFileService,
                        PikPakService pikPakService,
                        RestTemplateBuilder builder,
                        Environment environment) {
        this.objectMapper = objectMapper;
        this.shareRepository = shareRepository;
        this.aliasRepository = aliasRepository;
        this.settingRepository = settingRepository;
        this.siteRepository = siteRepository;
        this.accountRepository = accountRepository;
        this.pikPakAccountRepository = pikPakAccountRepository;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
        this.configFileService = configFileService;
        this.pikPakService = pikPakService;
        this.environment = environment;
        this.restTemplate = builder.rootUri("http://localhost:" + (appProperties.isHostmode() ? "5234" : "5244")).build();
        this.restTemplate1 = builder
                .defaultHeader(HttpHeaders.REFERER, "https://docs.qq.com/")
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .build();
    }

    @PostConstruct
    public void setup() {
        updateAListDriverType();
        loadOpenTokenUrl();

        pikPakService.readPikPak();

        List<Share> list = shareRepository.findAll();
        if (list.isEmpty()) {
            list = loadSharesFromFile();
        }

        list = list.stream().filter(e -> e.getId() < 7000).collect(Collectors.toList());
        list.addAll(loadLatestShare());

        loadAListShares(list);
        loadAListAlias();
        loadSites();
        pikPakService.loadPikPak();
        load115();
        configFileService.writeFiles();
        readTvTxt();

        if (accountRepository.count() > 0 || pikPakAccountRepository.count() > 0) {
            aListLocalService.startAListServer();
        }
    }

    private void load115() {
        Path file1 = Paths.get("/data/temp_transfer_folder_id.txt");
        Account account1 = accountRepository.getFirstByMasterTrue().orElse(new Account());
        if (Files.exists(file1)) {
            try {
                String tempFolderId = Files.readString(file1).trim();
                String openTokenUrl = settingRepository.findById(OPEN_TOKEN_URL).map(Setting::getValue).orElse("https://api.xhofe.top/alist/ali_open/token");
                String clientId = settingRepository.findById("open_api_client_id").map(Setting::getValue).orElse("");
                String clientSecret = settingRepository.findById("open_api_client_secret").map(Setting::getValue).orElse("");

                log.info("temp_transfer_folder_id: {}", tempFolderId);
                // settingRepository.save(new Setting(TEMP_FOLDER_ID, tempFolderId));
                log.info("update temp_transfer_folder_id");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.TempTransferFolderID', '" + tempFolderId + "') where driver = 'AliyundriveShare2Open'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.RefreshToken', '" + account1.getRefreshToken() + "') where driver = 'AliyundriveShare2Open'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.RefreshTokenOpen', '" + account1.getOpenToken() + "') where driver = 'AliyundriveShare2Open'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.oauth_token_url', '" + openTokenUrl + "') where driver = 'AliyundriveShare2Open'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.client_id', '" + clientId + "') where driver = 'AliyundriveShare2Open'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.client_secret', '" + clientSecret + "') where driver = 'AliyundriveShare2Open'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.refresh_token', '" + account1.getRefreshToken() + "') where driver = 'AliyundriveShare2Open'");
            } catch (Exception e) {
                throw new BadRequestException(e);
            } 
        } else {
            log.warn("temp_transfer_folder_id.txt转存文件夹不存在，请在阿里云盘手动创建并命名为 xiaoya-tvbox-temp ");
        }
        Path file = Paths.get("/data/ali2115.txt");
        if (Files.exists(file)) {
            try {
                List<String> lines = Files.readAllLines(file);
                if (lines.size() < 4) {
                    log.warn("ali2115.txt文件内容不完整，请检查文件");
                    return;
                }
                String purgeAliTemp = lines.get(0).split("=")[1];
                String[] parts = lines.get(1).split("=",2);
                String cookie = parts.length > 1 ? parts[1] : null;
                cookie = cookie.substring(1, cookie.length() - 1);
                String purgePan115Temp = lines.get(2).split("=")[1];
                String dirId = lines.get(3).split("=")[1];
                  
                log.info("update storage driver type");
                Utils.executeUpdate("update x_storages set driver = 'AliyundriveShare2Pan115' where driver = 'AliyundriveShare2Open'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.purge_ali_temp', '" + purgeAliTemp + "') where driver = 'AliyundriveShare2Pan115'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.cookie', '" + cookie + "') where driver = 'AliyundriveShare2Pan115'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.purge_pan115_temp', '" + purgePan115Temp + "') where driver = 'AliyundriveShare2Pan115'");
                Utils.executeUpdate("update x_storages set addition = json_set(addition, '$.dir_id', '" + dirId + "') where driver = 'AliyundriveShare2Pan115'");
                
            } catch (Exception e) {
                throw new BadRequestException(e);
            }
        } 
    }      
        
    public void loadAListAlias() {
        List<AListAlias> list = aliasRepository.findAll();
        if (list.isEmpty()) {
            return;
        }

        try {
            for (AListAlias alias : list) {
                try {
                    String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Alias',0,'work','{\"paths\":\"%s\"}','','2023-06-20 12:00:00+00:00',0,'name','asc','front',0,'302_redirect','');";
                    int count = Utils.executeUpdate(String.format(sql, alias.getId(), alias.getPath(), Utils.getAliasPaths(alias.getContent())));
                    log.info("insert Alias {}: {}, result: {}", alias.getId(), alias.getPath(), count);
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void loadSites() {
        for (Site site : siteRepository.findAll()) {
            if (site.getId() == 1 || site.isDisabled() || site.getUrl().startsWith("http://localhost")) {
                continue;
            }
            try {
                String sql = "INSERT INTO x_storages VALUES(%d,'/\uD83C\uDF8E我的套娃/%s',0,'AList V%d',0,'work','{\"root_folder_path\":\"%s\",\"url\":\"%s\",\"meta_password\":\"%s\",\"token\":\"%s\",\"username\":\"\",\"password\":\"\"}','','2023-06-20 12:00:00+00:00',0,'name','asc','front',0,'302_redirect','');";
                int count = Utils.executeUpdate(String.format(sql, 8000 + site.getId(), site.getName(), site.getVersion(), getFolder(site), site.getUrl(), site.getPassword(), site.getToken()));
                log.info("insert Site {}:{} {}, result: {}", site.getId(), site.getName(), site.getUrl(), count);
            } catch (Exception e) {
                log.warn("{}", e.getMessage());
            }
        }
    }

    private String getFolder(Site site) {
        if (StringUtils.isBlank(site.getFolder())) {
            return "/";
        }
        return site.getFolder();
    }

    private void loadOpenTokenUrl() {
        if (settingRepository.existsById(OPEN_TOKEN_URL)) {
            return;
        }

        try {
            String url = null;
            try {
                Path file = Paths.get("/data/open_token_url.txt");
                if (Files.exists(file)) {
                    url = Files.readString(file).trim();
                    log.debug("loadOpenTokenUrl {}", url);
                    settingRepository.save(new Setting(OPEN_TOKEN_URL, url));
                }
            } catch (Exception e) {
                log.warn("", e);
            }

            Path path = Paths.get("/opt/alist/data/config.json");
            if (Files.exists(path)) {
                String text = Files.readString(path);
                Map<String, Object> json = objectMapper.readValue(text, Map.class);
                if (url != null) {
                    json.put("opentoken_auth_url", url);
                    text = objectMapper.writeValueAsString(json);
                    Files.writeString(path, text);
                } else {
                    settingRepository.save(new Setting(OPEN_TOKEN_URL, (String) json.get("opentoken_auth_url")));
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public void updateOpenTokenUrl(OpenApiDto dto) {
        String url = dto.getUrl();
        try {
            Path path = Paths.get("/opt/alist/data/config.json");
            if (Files.exists(path)) {
                String text = Files.readString(path);
                Map<String, Object> json = objectMapper.readValue(text, Map.class);
                json.put("opentoken_auth_url", url);
                text = objectMapper.writeValueAsString(json);
                Files.writeString(path, text);
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            Path file = Paths.get("/data/open_token_url.txt");
            Files.writeString(file, url);
        } catch (Exception e) {
            log.warn("", e);
        }

        settingRepository.save(new Setting(OPEN_TOKEN_URL, url));
        settingRepository.save(new Setting("open_api_client_id", dto.getClientId() == null ? "" : dto.getClientId().trim()));
        settingRepository.save(new Setting("open_api_client_secret", dto.getClientSecret() == null ? "" : dto.getClientSecret().trim()));
        Utils.executeUpdate("UPDATE x_setting_items SET value = '" + url + "' WHERE key = 'open_token_url'");
        Utils.executeUpdate("UPDATE x_setting_items SET value = '" + dto.getClientId().trim() + "' WHERE key = 'open_api_client_id'");
        Utils.executeUpdate("UPDATE x_setting_items SET value = '" + dto.getClientSecret().trim() + "' WHERE key = 'open_api_client_secret'");
    }

    private List<Share> loadSharesFromFile() {
        List<Share> list = new ArrayList<>();
        Path path = Paths.get("/data/alishare_list.txt");
        if (Files.exists(path)) {
            try {
                log.info("loading share list from file");
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 1) {
                        try {
                            Share share = new Share();
                            share.setId(shareId++);
                            share.setPath(parts[0]);
                            share.setShareId(parts[1]);
                            if (parts.length > 2) {
                                share.setFolderId(parts[2]);
                            }
                            share.setType(0);
                            list.add(share);
                        } catch (Exception e) {
                            log.warn("", e);
                        }
                    }
                }
                shareRepository.saveAll(list);
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        list.addAll(loadPikPakFromFile());
        return list;
    }

    private List<Share> loadPikPakFromFile() {
        List<Share> list = new ArrayList<>();
        Path path = Paths.get("/data/pikpakshare_list.txt");
        if (Files.exists(path)) {
            try {
                log.info("loading PikPak share list from file");
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 1) {
                        try {
                            Share share = new Share();
                            share.setId(shareId++);
                            share.setPath(parts[0]);
                            share.setShareId(parts[1]);
                            if (parts.length > 2) {
                                share.setFolderId(parts[2]);
                            } else {
                                share.setFolderId("");
                            }
                            share.setType(1);
                            list.add(share);
                        } catch (Exception e) {
                            log.warn("", e);
                        }
                    }
                }
                shareRepository.saveAll(list);
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        return list;
    }

    public int importShares(SharesDto dto) {
        int count = 0;
        log.info("import share list");
        for (String line : dto.getContent().split("\n")) {
            String[] parts = line.trim().split("\\s+");
            log.debug("import {} {}", parts.length, line);
            if (parts.length > 1) {
                try {
                    Share share = new Share();
                    share.setId(shareId);
                    share.setPath(parts[0]);
                    share.setShareId(parts[1]);
                    share.setType(dto.getType());
                    //*if (share.getType() == 1) {
                    //    share.setFolderId("");
                    //}
                    if (parts.length > 2) {
                        share.setFolderId(parts[2]);
                    }
                    if (parts.length > 3) {
                        share.setPassword(parts[3]);
                    }
                    share.setPath(getMountPath(share));
                    if (shareRepository.existsByPath(share.getPath())) {
                        continue;
                    }
                    create(share);
                    count++;
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        }

        log.info("loaded {} shares", count);
        return count;
    }

    public String exportShare(HttpServletResponse response, int type) {
        List<Share> list = shareRepository.findByType(type);
        StringBuilder sb = new StringBuilder();
        String fileName;
        if (type == 1) {
            fileName = "pikpak_share_list.txt";
        } else if (type == 5) {
            fileName = "quark_share_list.txt";
        } else if (type == 7) {
            fileName = "uc_share_list.txt";
        } else if (type == 8) {
            fileName = "115_share_list.txt"; 
        } else {
            fileName = "ali_share_list.txt";
        }

        for (Share share : list) {
            sb.append(getMountPath(share).replace(" ", "")).append("  ").append(share.getShareId())
                .append("  ").append(StringUtils.isBlank(share.getFolderId()) ? "root" : share.getFolderId()).append("  ").append(share.getPassword()).append("\n");
        }

        log.info("export {} shares to file: {}", list.size(), fileName);
        response.addHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.setContentType("text/plain");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return sb.toString();
    }

    private void loadAListShares(List<Share> list) {
        if (list.isEmpty()) {
            return;
        }

        boolean pikpak = false;
        try {
            Account account1 = accountRepository.getFirstByMasterTrue().orElse(new Account());
            PikPakAccount account2 = pikPakAccountRepository.getFirstByMasterTrue().orElse(new PikPakAccount());
            for (Share share : list) {
                try {
                    if (share.getType() == null || share.getType() == 0) {
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"root\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), account1.getRefreshToken(), account1.getOpenToken(), share.getShareId(), share.getPassword(), share.getFolderId()));
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                    } else if (share.getType() == 1) {
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'PikPakShare',30,'work','{\"root_folder_id\":\"%s\",\"username\":\"%s\",\"password\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getFolderId(), account2.getUsername(), account2.getPassword(), share.getShareId(), share.getPassword()));
                        pikpak = true;
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                    } else if (share.getType() == 2) {
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Quark',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'native_proxy','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getFolderId()));
                        log.info("insert Share {} : {}, result: {}", share.getId(), getMountPath(share), count);
                        Utils.executeUpdate("INSERT INTO x_setting_items VALUES('quark_cookie','" + share.getCookie() + "','','text','',1,0);");
                    } else if (share.getType() == 6) {
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UC',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'native_proxy','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getFolderId()));
                        log.info("insert Share {} : {}, result: {}", share.getId(), getMountPath(share), count);
                        Utils.executeUpdate("INSERT INTO x_setting_items VALUES('uc_cookie','" + share.getCookie() + "','','text','',1,0);");
                    } else if (share.getType() == 3) {
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":56}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getPassword(), share.getFolderId()));
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                        Utils.executeUpdate("INSERT INTO x_setting_items VALUES('115_cookie','" + share.getCookie() + "','','text','',1,0);");
                    } else if (share.getType() == 8) {
                        String cookie = "";
                        Path file = Paths.get("/data/115_cookie.txt");
                        if (Files.exists(file)) {
                            try {
                                cookie = Files.readString(file);
                            } catch (Exception e) {
                                log.warn("", e);
                            }
                        }
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Share',1440,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"qrcode_token\":\"\",\"qrcode_source\":\"linux\",\"page_size\":20,\"limit_rate\":2,\"share_code\":\"%s\",\"receive_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'302_redirect','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                    } else if (share.getType() == 4) {
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Local',30,'work','{\"root_folder_path\":\"%s\",\"thumbnail\":false,\"thumb_cache_folder\":\"\",\"show_hidden\":true,\"mkdir_perm\":\"777\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'native_proxy','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), share.getPath(), share.getFolderId()));
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getPath(), share.getFolderId(), count);
                    } else if (share.getType() == 5) {
                        String cookie = "";
                        Path file = Paths.get("/data/quark_cookie.txt");
                        if (Files.exists(file)) {
                            try {
                                cookie = Files.readString(file);
                            } catch (Exception e) {
                                log.warn("", e);
                            }
                        }
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'QuarkShare',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"asc\",\"share_id\":\"%s\",\"pass_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'native_proxy','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                    } else if (share.getType() == 7) {
                        String cookie = "";
                        Path file = Paths.get("/data/uc_cookie.txt");
                        if (Files.exists(file)) {
                            try {
                                cookie = Files.readString(file);
                            } catch (Exception e) {
                                log.warn("", e);
                            }
                        }
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UCShare',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"asc\",\"share_id\":\"%s\",\"pass_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'302_redirect','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                    }
                    if (share.getId() < 6000) {
                        shareId = Math.max(shareId, share.getId() + 1);
                    }
                    if (share.getType() == null) {
                        share.setType(0);
                        shareRepository.save(share);
                    }
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        if (pikpak) {
            pikPakService.updateIndexFile();
        }
    }

    private void updateAListDriverType() {
        try {
            log.info("update storage driver type");
            Utils.executeUpdate("update x_storages set driver = 'AliyundriveShare2Open' where driver = 'AliyundriveShare'");
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private void updateCookieByApi(String key, String cookie) {
        int status = aListLocalService.getAListStatus();
        if (status == 1) {
            Utils.executeUpdate("INSERT INTO x_setting_items VALUES('" + key + "','" + cookie + "','','text','',1,0);");
            throw new BadRequestException("AList服务启动中");
        }

        String token = status == 2 ? login() : "";
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", List.of(token));
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("type", "text");
        body.put("flag", 1);
        body.put("value", cookie);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/setting/update", HttpMethod.POST, entity, String.class);
        log.info("updateCookieByApi {} response: {}", response.getBody());
    }

    public String login() {
        String username = "atv";
        String password = settingRepository.findById(ATV_PASSWORD).map(Setting::getValue).orElseThrow(BadRequestException::new);
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        LoginResponse response = restTemplate.postForObject("/api/auth/login", request, LoginResponse.class);
        log.debug("AList login response: {}", response);
        return response.getData().getToken();
    }

    private String getMountPath(Share share) {
        String path = share.getPath();
        if (path.startsWith("/")) {
            return path;
        }
        if (share.getType() == null || share.getType() == 0) {
            return "/\uD83C\uDE34我的阿里分享/" + path;
        } else if (share.getType() == 1) {
            return "/\uD83D\uDD78️我的PikPak分享/" + path;
        } else if (share.getType() == 2) {
            return "/\uD83C\uDF1E我的夸克网盘/" + path;
        } else if (share.getType() == 6) {
            return "/\uD83C\uDF1E我的UC网盘/" + path;
        } else if (share.getType() == 3) {
            return "/115网盘/" + path;
        } else if (share.getType() == 5) {
            return "/我的夸克分享/" + path;
        } else if (share.getType() == 7) {
            return "/我的UC分享/" + path;
        } else if (share.getType() == 8) {
            return "/我的115分享/" + path;
        }
        return path;
    }

    private void readTvTxt() {
        Path file = Paths.get("/data/tv.txt");
        if (Files.exists(file)) {
            log.info("read tv from file");
            try {
                StringBuilder sb = parseTvFile(file);
                Utils.executeUpdate("INSERT INTO x_storages VALUES(2050,'/\uD83C\uDDF9\uD83C\uDDFB直播/我的自选',0,'UrlTree',0,'work','{\"url_structure\":\"" + sb + "\",\"head_size\":false}','','2023-06-20 12:00:00+00:00',0,'name','','',0,'302_redirect','');");
            } catch (Exception e) {
                log.warn("", e);
            }
        }
    }

    private static StringBuilder parseTvFile(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String[] parts = line.trim().split(",");
            if ((parts.length == 1 || "#genre#".equals(parts[1])) && StringUtils.isNotBlank(parts[0])) {
                sb.append(parts[0]).append(":\\n");
            } else if (parts.length == 2 && !StringUtils.isAnyBlank(parts[0], parts[1])) {
                sb.append("  ").append(parts[0]).append(".m3u8:").append(parts[1]).append("\\n");
            } else {
                sb.append("\\n");
            }
        }
        return sb;
    }

    public Page<Share> list(Pageable pageable) {
        return shareRepository.findAll(pageable);
    }

    public String getQuarkCookie(String id) {
        String aliSecret = settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElse("");
        if (aliSecret.equals(id)) {
            return shareRepository.findByType(2).stream().findFirst().map(Share::getCookie).orElse("").trim();
        }
        return "";
    }

    private static final Pattern SHARE_115_LINK = Pattern.compile("https://115.com/s/(\\w+)\\?password=(\\w+)#?");

    private void parseShare(Share share) {
        if (StringUtils.isBlank(share.getShareId())) {
            return;
        }

        String url = share.getShareId();
        var m = SHARE_115_LINK.matcher(url);
        if (m.find()) {
            share.setShareId(m.group(1));
            share.setPassword(m.group(2));
            return;
        }

        int index = url.indexOf("/s/");
        if (index > 0) {
            url = url.substring(index + 3);
        }
        index = url.indexOf("#/list/share/");
        if (index > 0) {
            String path = url.substring(index + 13);
            String[] parts = path.split("/");
            path = parts[parts.length - 1].split("-")[0];
            share.setFolderId(path);
            url = url.substring(0, index);
        }
        index = url.indexOf('?');
        if (index > 0) {
            url = url.substring(0, index);
        }

        String[] parts = url.split("/");
        if (parts.length == 3 && "folder".equals(parts[1])) {
            share.setShareId(parts[0]);
            share.setFolderId(parts[2]);
        } else if (parts.length == 2) {
            share.setShareId(parts[0]);
            share.setFolderId(parts[1]);
        } else {
            share.setShareId(parts[0]);
        }
    }

    public Share create(Share share) {
        aListLocalService.validateAListStatus();
        validate(share);
        parseShare(share);
        Account account1 = accountRepository.getFirstByMasterTrue().orElse(new Account());

        try {
            String token = accountService.login();
            share.setId(shareId++);

            int result = 0;
            if (share.getType() == null || share.getType() == 0) {
                // log.info("share.getType() = " + share.getType());  // 输出share.getType()的值
                List<String> lines = Files.readAllLines(Paths.get("/data/temp_transfer_folder_id.txt"));
                // log.info("lines = " + lines);  // 输出lines的值
                String folderId = "";  // 默认值
                if (!lines.isEmpty()) {
                    folderId = lines.get(0);
                }
                log.info("temp_transfer_folder_id = " + folderId);
                String driverType = Files.exists(Paths.get("/data/ali2115.txt")) ? "AliyundriveShare2Pan115" : "AliyundriveShare2Open";
                log.info("driverType = " + driverType);
                String openTokenUrl = settingRepository.findById(OPEN_TOKEN_URL).map(Setting::getValue).orElse("https://api.xhofe.top/alist/ali_open/token");
                String clientId = settingRepository.findById("open_api_client_id").map(Setting::getValue).orElse("");
                String clientSecret = settingRepository.findById("open_api_client_secret").map(Setting::getValue).orElse("");
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'%s',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"%s\",\"client_id\":\"%s\",\"client_secret\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'302_redirect','');";
                result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), driverType, account1.getRefreshToken(), account1.getOpenToken(), folderId, share.getShareId(), share.getPassword(), share.getFolderId(), openTokenUrl, clientId, clientSecret));
                
                // String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"\",\"RefreshTokenOpen\":\"\",\"TempTransferFolderID\":\"root\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                // result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getShareId(), share.getPassword(), share.getFolderId()));
            } else if (share.getType() == 1) {
                PikPakAccount account = pikPakAccountRepository.getFirstByMasterTrue().orElseThrow(BadRequestException::new);
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'PikPakShare',30,'work','{\"root_folder_id\":\"%s\",\"username\":\"%s\",\"password\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getFolderId(), account.getUsername(), account.getPassword(), share.getShareId(), share.getPassword()));
            } else if (share.getType() == 2) {
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Quark',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getFolderId()));
                log.info("insert Share {} : {}, result: {}", share.getId(), getMountPath(share), count);
                updateCookieByApi("quark_cookie", share.getCookie());
            } else if (share.getType() == 6) {
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UC',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getFolderId()));
                log.info("insert Share {} : {}, result: {}", share.getId(), getMountPath(share), count);
                updateCookieByApi("uc_cookie", share.getCookie());
            } else if (share.getType() == 3) {
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":56}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getPassword(), share.getFolderId()));
                log.info("insert Share {} : {}, result: {}", share.getId(), getMountPath(share), count);
                updateCookieByApi("115_cookie", share.getCookie());
            } else if (share.getType() == 8) {
                String cookie = "";
                Path file = Paths.get("/data/115_cookie.txt");
                if (Files.exists(file)) {
                    try {
                        cookie = Files.readString(file);
                    } catch (Exception e) {
                        log.warn("", e);
                    }
                }
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Share',1440,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"qrcode_token\":\"\",\"qrcode_source\":\"linux\",\"page_size\":20,\"limit_rate\":2,\"share_code\":\"%s\",\"receive_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'302_redirect','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                
                // String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Share',30,'work','{\"share_code\":\"%s\",\"receive_code\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                // result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getShareId(), share.getPassword(), share.getFolderId()));
            } else if (share.getType() == 4) {
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Local',30,'work','{\"root_folder_path\":\"%s\",\"thumbnail\":false,\"thumb_cache_folder\":\"\",\"show_hidden\":true,\"mkdir_perm\":\"777\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), share.getPath(), share.getFolderId()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getPath(), share.getFolderId(), count);
            } else if (share.getType() == 5) {
                String cookie = "";
                Path file = Paths.get("/data/quark_cookie.txt");
                if (Files.exists(file)) {
                    try {
                        cookie = Files.readString(file);
                    } catch (Exception e) {
                        log.warn("", e);
                    }
                }
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'QuarkShare',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"asc\",\"share_id\":\"%s\",\"pass_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'native_proxy','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                
                // String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'QuarkShare',30,'work','{\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                // result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getShareId(), share.getPassword(), share.getFolderId()));
            } else if (share.getType() == 7) {
                String cookie = "";
                Path file = Paths.get("/data/uc_cookie.txt");
                if (Files.exists(file)) {
                    try {
                        cookie = Files.readString(file);
                    } catch (Exception e) {
                        log.warn("", e);
                    }
                }
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UCShare',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"asc\",\"share_id\":\"%s\",\"pass_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'302_redirect','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                
                // String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UCShare',30,'work','{\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                // result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getShareId(), share.getPassword(), share.getFolderId()));
            }
            log.info("insert result: {}", result);
            shareRepository.save(share);
            enableStorage(share.getId(), token);
        } catch (Exception e) {
            log.warn("", e);
            throw new BadRequestException(e);
        }
        return share;
    }

    public Share update(Integer id, Share share) {
        aListLocalService.validateAListStatus();
        validate(share);
        parseShare(share);
        Account account1 = accountRepository.getFirstByMasterTrue().orElse(new Account());

        share.setId(id);
        shareRepository.save(share);

        String token = accountService.login();
        try {
            deleteStorage(id, token);

            int result = 0;
            if (share.getType() == null || share.getType() == 0) {
                List<String> lines = Files.readAllLines(Paths.get("/data/temp_transfer_folder_id.txt"));
                // log.info("lines = " + lines);  // 输出lines的值
                String folderId = "";  // 默认值
                if (!lines.isEmpty()) {
                    folderId = lines.get(0);
                }
                log.info("temp_transfer_folder_id = " + folderId);
                String driverType = Files.exists(Paths.get("/data/ali2115.txt")) ? "AliyundriveShare2Pan115" : "AliyundriveShare2Open";
                log.info("driverType = " + driverType);
                String openTokenUrl = settingRepository.findById(OPEN_TOKEN_URL).map(Setting::getValue).orElse("https://api.xhofe.top/alist/ali_open/token");
                String clientId = settingRepository.findById("open_api_client_id").map(Setting::getValue).orElse("");
                String clientSecret = settingRepository.findById("open_api_client_secret").map(Setting::getValue).orElse("");
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'%s',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"%s\",\"client_id\":\"%s\",\"client_secret\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'302_redirect','');";
                result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), driverType, account1.getRefreshToken(), account1.getOpenToken(), folderId, share.getShareId(), share.getPassword(), share.getFolderId(), openTokenUrl, clientId, clientSecret));
                
                // String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"\",\"RefreshTokenOpen\":\"\",\"TempTransferFolderID\":\"root\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                // result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getShareId(), share.getPassword(), share.getFolderId()));
            } else if (share.getType() == 1) {
                PikPakAccount account = pikPakAccountRepository.getFirstByMasterTrue().orElseThrow(BadRequestException::new);
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'PikPakShare',30,'work','{\"root_folder_id\":\"%s\",\"username\":\"%s\",\"password\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getFolderId(), account.getUsername(), account.getPassword(), share.getShareId(), share.getPassword()));
            } else if (share.getType() == 2) {
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Quark',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getFolderId()));
                log.info("insert Share {} : {}, result: {}", share.getId(), getMountPath(share), count);
                updateCookieByApi("quark_cookie", share.getCookie());
            } else if (share.getType() == 6) {
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UC',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getFolderId()));
                log.info("insert Share {} : {}, result: {}", share.getId(), getMountPath(share), count);
                updateCookieByApi("uc_cookie", share.getCookie());
            } else if (share.getType() == 3) {
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Cloud',30,'work','{\"cookie\":\"%s\",\"qrcode_token\":\"%s\",\"root_folder_id\":\"%s\",\"page_size\":56}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getCookie(), share.getPassword(), share.getFolderId()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                updateCookieByApi("115_cookie", share.getCookie());
            } else if (share.getType() == 8) {
                String cookie = "";
                Path file = Paths.get("/data/115_cookie.txt");
                if (Files.exists(file)) {
                    try {
                        cookie = Files.readString(file);
                    } catch (Exception e) {
                        log.warn("", e);
                    }
                }
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Share',1440,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"qrcode_token\":\"\",\"qrcode_source\":\"linux\",\"page_size\":20,\"limit_rate\":2,\"share_code\":\"%s\",\"receive_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'302_redirect','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                
                // String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'115 Share',30,'work','{\"share_code\":\"%s\",\"receive_code\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                // result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getShareId(), share.getPassword(), share.getFolderId()));
            } else if (share.getType() == 4) {
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Local',30,'work','{\"root_folder_path\":\"%s\",\"thumbnail\":false,\"thumb_cache_folder\":\"\",\"show_hidden\":true,\"mkdir_perm\":\"777\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'native_proxy','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), share.getPath(), share.getFolderId()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getPath(), share.getFolderId(), count);
            } else if (share.getType() == 5) {
                String cookie = "";
                Path file = Paths.get("/data/quark_cookie.txt");
                if (Files.exists(file)) {
                    try {
                        cookie = Files.readString(file);
                    } catch (Exception e) {
                        log.warn("", e);
                    }
                }
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'QuarkShare',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"asc\",\"share_id\":\"%s\",\"pass_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'native_proxy','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                
                // String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'QuarkShare',30,'work','{\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                // result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getShareId(), share.getPassword(), share.getFolderId()));
            } else if (share.getType() == 7) {
                String cookie = "";
                Path file = Paths.get("/data/uc_cookie.txt");
                if (Files.exists(file)) {
                    try {
                        cookie = Files.readString(file);
                    } catch (Exception e) {
                        log.warn("", e);
                    }
                }
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UCShare',30,'work','{\"cookie\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"asc\",\"share_id\":\"%s\",\"pass_code\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','front',0,'302_redirect','');";
                int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), cookie, share.getFolderId(), share.getShareId(), share.getPassword()));
                log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                
                // String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'UCShare',30,'work','{\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                // result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getShareId(), share.getPassword(), share.getFolderId()));
            }
            log.info("insert result: {}", result);

            enableStorage(id, token);
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        return share;
    }

    private void validate(Share share) {
        if (StringUtils.isBlank(share.getPath())) {
            throw new BadRequestException("挂载路径不能为空");
        }
        if (share.getPath().equals("/")) {
            throw new BadRequestException("挂载路径不能为/");
        }
        if (share.getPath().contains(" ")) {
            throw new BadRequestException("挂载路径不能包含空格");
        }

        if (share.getType() == 2 || share.getType() == 6) {
            if (StringUtils.isBlank(share.getCookie())) {
                throw new BadRequestException("Cookie不能为空");
            }
        } else if (share.getType() == 3) {
            if (StringUtils.isBlank(share.getCookie()) && StringUtils.isBlank(share.getPassword())) {
                throw new BadRequestException("Cookie和Token至少填写一个");
            }
        } else if (share.getType() != 4) {
            if (StringUtils.isBlank(share.getShareId())) {
                throw new BadRequestException("分享ID不能为空");
            }
        }

        if (StringUtils.isBlank(share.getFolderId())) {
            if (share.getType() == 2 || share.getType() == 3 || share.getType() == 5 || share.getType() == 7) {
                share.setFolderId("0");
            } else if (share.getType() == 0) {
                share.setFolderId("root");
            } else if (share.getType() == 4) {
                share.setFolderId("/");
            }
        }

        if ((share.getType() == 1 || share.getType() == 8) && "root".equals(share.getFolderId())) {
            share.setFolderId("");
        }

        if (share.getCookie() != null) {
            share.setCookie(share.getCookie().trim());
        }
    }

    public void enableStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("enable storage response: {}", response.getBody());
    }

    public void deleteShares(List<Integer> ids) {
        aListLocalService.validateAListStatus();
        for (Integer id : ids) {
            try {
                shareRepository.deleteById(id);
                String token = accountService.login();
                deleteStorage(id, token);
            } catch (Exception e) {
                log.warn("{}", e.getMessage());
            }
        }
    }

    public void deleteShare(Integer id) {
        aListLocalService.validateAListStatus();
        shareRepository.deleteById(id);
        String token = accountService.login();
        deleteStorage(id, token);
    }

    public void deleteStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/storage/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete storage response: {}", response.getBody());
    }

    private Integer getType(String driver) {
        switch (driver) {
            case "PikPakShare":
                return 1;
            case "Quark":
                return 2;
            default:
                return 0;
        }
    }

    public Object listStorages(Pageable pageable) {
        aListLocalService.validateAListStatus();
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(accountService.login()));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        ResponseEntity<Object> response = restTemplate.exchange("/api/admin/storage/failed?page=" + pageable.getPageNumber() + "&per_page=" + pageable.getPageSize(), HttpMethod.GET, entity, Object.class);
        return response.getBody();
    }

    public Response reloadStorage(Integer id) {
        aListLocalService.validateAListStatus();
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(accountService.login()));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        ResponseEntity<Response> response = restTemplate.exchange("/api/admin/storage/reload?id=" + id, HttpMethod.POST, entity, Response.class);
        log.debug("reload storage {}: {}", id, response.getBody());
        return response.getBody();
    }

    private List<Share> loadLatestShare() {
        List<Share> shares = new ArrayList<>();
        if (!environment.matchesProfiles("xiaoya")) {
            return shares;
        }

        try {
            Share share = new Share();
            share.setType(0);
            share.setId(7000);
            share.setShareId("cdqCsAWD9wC");
            share.setPassword("6666");
            share.setFolderId("635151fc53641440ad95492c8174c57584c56f68");
            share.setPath("/\uD83C\uDE34我的阿里分享/Tacit0924");
            shares.add(shareRepository.save(share));
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            Share share = new Share();
            share.setType(0);
            share.setId(7001);
            share.setShareId("mxAfB6eRgY4");
            share.setFolderId("63833bb670c164d4eeb14aa09c62ee770d9112ba");
            share.setPath("/\uD83C\uDE34我的阿里分享/近期更新");
            shares.add(shareRepository.save(share));
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            Share share = new Share();
            share.setType(0);
            share.setId(7002);
            share.setShareId("4ydLxf7VgH7");
            share.setFolderId("6411b6c459de9db58ea5439cb7f537bbed4f4f4b");
            share.setPath("/\uD83C\uDE34我的阿里分享/每日更新");
            shares.add(shareRepository.save(share));
        } catch (Exception e) {
            log.warn("", e);
        }
        return shares;
    }

    @Scheduled(initialDelay = 1800_000, fixedDelay = 1800_000)
    public void syncCookies() {
        if (aListLocalService.getAListStatus() != 2) {
            return;
        }
        var cookie = aListLocalService.getSetting("quark_cookie");
        log.debug("quark_cookie={}", cookie);
        saveCookie(2, cookie);
        cookie = aListLocalService.getSetting("uc_cookie");
        log.debug("uc_cookie={}", cookie);
        saveCookie(6, cookie);
        cookie = aListLocalService.getSetting("115_cookie");
        log.debug("115_cookie={}", cookie);
        saveCookie(3, cookie);
    }

    private void saveCookie(int type, SettingResponse response) {
        if (response.getCode() == 200) {
            List<Share> shares = shareRepository.findByType(type);
            if (!shares.isEmpty()) {
                Share share = shares.get(shares.size() - 1);
                share.setCookie(response.getData().getValue());
                shareRepository.save(share);
            }
        }
    }
}
