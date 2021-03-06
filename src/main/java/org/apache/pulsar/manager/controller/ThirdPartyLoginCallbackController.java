/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.pulsar.manager.controller;

import com.google.common.collect.Maps;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.manager.entity.*;
import org.apache.pulsar.manager.service.JwtService;
import org.apache.pulsar.manager.service.ThirdPartyLoginService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Optional;

/**
 * Callback function of third party platform login.
 */
@Slf4j
@Controller
@RequestMapping(value = "/pulsar-manager/third-party-login")
@Api(description = "Calling the request below this class does not require authentication because " +
        "the user has not logged in yet.")
@Validated
public class ThirdPartyLoginCallbackController {

    @Value("${github.client.id}")
    private String githubClientId;

    @Value("${github.login.host}")
    private String githubLoginHost;

    @Value("${github.redirect.host}")
    private String githubRedirectHost;

    @Value("${github.assigned.role}")
    private String githubAssignedRole;

    private final ThirdPartyLoginService thirdPartyLoginService;

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private RolesRepository rolesRepository;

    @Autowired
    private RoleBindingRepository roleBindingRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    public ThirdPartyLoginCallbackController(ThirdPartyLoginService thirdPartyLoginService) {
        this.thirdPartyLoginService = thirdPartyLoginService;
    }

    @ApiOperation(value = "When use pass github authentication, Github platform will carry code parameter to call " +
            "back this address actively. At this time, we can request token and get user information through " +
            "this code." +
            "Reference document: https://developer.github.com/apps/building-oauth-apps/authorizing-oauth-apps/")
    @ApiResponses({
            @ApiResponse(code = 302, message = ""),
            @ApiResponse(code = 401, message = "Authentication failed, please check carefully"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @RequestMapping(value = "/callback/github")
    @ResponseStatus(HttpStatus.FOUND)
    public void githubCallbackIndex(@RequestParam() String code, HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, String> parameters = Maps.newHashMap();
        parameters.put("code", code);
        String accessToken = thirdPartyLoginService.getAuthToken(parameters);
        Map<String, String> authenticationMap = Maps.newHashMap();
        authenticationMap.put("access_token", accessToken);
        UserInfoEntity userInfoEntity = thirdPartyLoginService.getUserInfo(authenticationMap);
        if (userInfoEntity == null) {
            response.sendError(401, "Authentication failed, please check carefully");
            return;
        }
        log.info("Authentication successful, logging in");
        UserInfoEntity localUserInfoEntity = syncUser(userInfoEntity);
        assignRole(localUserInfoEntity);
        String token = jwtService.toToken(localUserInfoEntity.getAccessToken() + System.currentTimeMillis());
        localUserInfoEntity.setAccessToken(token);
        usersRepository.update(localUserInfoEntity);
        jwtService.setToken(request.getSession().getId(), token);
        response.addHeader("Set-Cookie", "Admin-Token=" + token + "; Path=/");
        response.addHeader("Set-Cookie", "username=" + localUserInfoEntity.getName() + "; Path=/");
        response.addHeader("Set-Cookie", "tenant=" + localUserInfoEntity.getName() + "; Path=/");
        response.addHeader("Location", "/");
        response.setStatus(302);
    }

    protected UserInfoEntity syncUser(UserInfoEntity externalUserInfoEntity) {
        Optional<UserInfoEntity> localUserInfoEntityOptional = usersRepository.findByUserName(externalUserInfoEntity.getName());
        UserInfoEntity localUserInfoEntity;
        if (localUserInfoEntityOptional.isPresent()) {
            localUserInfoEntity = localUserInfoEntityOptional.get();
            localUserInfoEntity.setAccessToken(externalUserInfoEntity.getAccessToken());
            usersRepository.update(localUserInfoEntity);
        } else {
            localUserInfoEntity = new UserInfoEntity();
            localUserInfoEntity.setAccessToken(externalUserInfoEntity.getAccessToken());
            localUserInfoEntity.setCompany(externalUserInfoEntity.getCompany());
            localUserInfoEntity.setLocation(externalUserInfoEntity.getLocation());
            localUserInfoEntity.setEmail(externalUserInfoEntity.getEmail());
            localUserInfoEntity.setName(externalUserInfoEntity.getName());
            localUserInfoEntity.setUserId(usersRepository.save(localUserInfoEntity));
        }
        return localUserInfoEntity;
    }

    protected void assignRole(UserInfoEntity userInfoEntity) {
        if (!githubAssignedRole.isEmpty()) {
            Optional<RoleInfoEntity> roleInfoEntityOptional = rolesRepository.findByRoleName(githubAssignedRole, "admin");
            if (roleInfoEntityOptional.isPresent()) {
                RoleInfoEntity roleInfoEntity = roleInfoEntityOptional.get();
                Optional<RoleBindingEntity> roleBindingEntityOptional = roleBindingRepository.findByUserIdAndRoleId(userInfoEntity.getUserId(), roleInfoEntity.getRoleId());
                if (!roleBindingEntityOptional.isPresent()) {
                    RoleBindingEntity roleBindingEntity = new RoleBindingEntity();
                    roleBindingEntity.setName(userInfoEntity.getName());
                    roleBindingEntity.setRoleId(roleInfoEntity.getRoleId());
                    roleBindingEntity.setUserId(userInfoEntity.getUserId());
                    roleBindingRepository.save(roleBindingEntity);
                }
            } else {
                log.error("Cannot assign role. Role " + githubAssignedRole + " does not exist.");
            }
        }
    }

    @ApiOperation(value = "Github's third-party authorized login address, HTTP GET request, needs to carry " +
            "client_id and redirect_host parameters. Parameter client_id and redirect_host needs to be applied " +
            "from github platform https://developer.github.com/apps/building-oauth-apps/creating-an-oauth-app/.")
    @ApiResponses({
            @ApiResponse(code = 302, message = ""),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    @RequestMapping(value = "/github/login", method = RequestMethod.GET)
    @ResponseStatus(HttpStatus.FOUND)
    public String getGithubLoginUrl() throws UnsupportedEncodingException {
        String url = githubLoginHost + "?access_type=online&client_id=" + githubClientId + "&scope=read:org&redirect_uri=" +
                URLEncoder.encode(githubRedirectHost + "/pulsar-manager/third-party-login/callback/github");
        return "redirect:" + url;
    }
}
