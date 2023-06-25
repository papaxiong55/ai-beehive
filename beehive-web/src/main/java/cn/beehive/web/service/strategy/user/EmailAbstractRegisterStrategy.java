package cn.beehive.web.service.strategy.user;

import cn.beehive.base.constant.ApplicationConstant;
import cn.beehive.base.domain.entity.EmailVerifyCodeDO;
import cn.beehive.base.domain.entity.FrontUserBaseDO;
import cn.beehive.base.domain.entity.FrontUserExtraBindingDO;
import cn.beehive.base.domain.entity.FrontUserExtraEmailDO;
import cn.beehive.base.enums.EmailBizTypeEnum;
import cn.beehive.base.enums.FrontUserRegisterTypeEnum;
import cn.beehive.base.exception.ServiceException;
import cn.beehive.base.resource.email.EmailRegisterLoginConfig;
import cn.beehive.base.util.EmailUtil;
import cn.beehive.web.domain.request.RegisterFrontUserForEmailRequest;
import cn.beehive.web.domain.vo.LoginInfoVO;
import cn.beehive.web.domain.vo.UserInfoVO;
import cn.beehive.web.service.EmailService;
import cn.beehive.web.service.EmailVerifyCodeService;
import cn.beehive.web.service.FrontUserBaseService;
import cn.beehive.web.service.FrontUserExtraBindingService;
import cn.beehive.web.service.FrontUserExtraEmailService;
import cn.beehive.web.service.SysFrontUserLoginLogService;
import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

import static cn.beehive.base.constant.ApplicationConstant.FRONT_JWT_EXTRA_USER_ID;
import static cn.beehive.base.constant.ApplicationConstant.FRONT_JWT_USERNAME;

/**
 * 邮箱注册策略
 *
 * @author CoDeleven
 */
@Lazy
@Component("EmailRegisterStrategy")
public class EmailAbstractRegisterStrategy extends AbstractRegisterTypeStrategy {

    @Resource
    private FrontUserExtraEmailService userExtraEmailService;

    @Resource
    private FrontUserBaseService baseUserService;

    @Resource
    private EmailVerifyCodeService emailVerifyCodeService;

    @Resource
    private FrontUserExtraBindingService bindingService;

    @Resource
    private EmailService emailService;

    @Resource
    private SysFrontUserLoginLogService loginLogService;

    @Override
    public boolean identityUsed(String identity) {
        return userExtraEmailService.isUsed(identity);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void checkVerifyCode(String identity, String verifyCode) {
        // 校验邮箱验证码
        EmailVerifyCodeDO availableVerifyCode = emailVerifyCodeService.findAvailableByVerifyCode(verifyCode);
        if (Objects.isNull(availableVerifyCode)) {
            throw new ServiceException("验证码不存在或已过期，请重新发起...");
        }
        // 验证通过，生成基础用户信息并做绑定
        FrontUserBaseDO baseUser = baseUserService.createEmptyBaseUser();
        // 获取邮箱信息表
        FrontUserExtraEmailDO emailExtraInfo = userExtraEmailService.getUnverifiedEmailAccount(availableVerifyCode.getToEmailAddress());
        // 绑定两张表
        bindingService.bindEmail(baseUser, emailExtraInfo);
        // 验证完毕，写入日志
        emailVerifyCodeService.verifySuccess(availableVerifyCode);
        // 设置邮箱已验证
        userExtraEmailService.verifySuccess(emailExtraInfo);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Boolean register(RegisterFrontUserForEmailRequest request) {
        // 校验邮箱注册权限
        EmailRegisterLoginConfig emailRegisterAccountConfig = EmailUtil.getRegisterAccountConfig();
        emailRegisterAccountConfig.checkRegisterPermission(request.getIdentity());

        // 查找邮箱账号是否存在
        FrontUserExtraEmailDO existsEmailDO = userExtraEmailService.getUnverifiedEmailAccount(request.getIdentity());
        String salt = RandomUtil.randomString(6);
        // 构建新的邮箱信息
        if (Objects.isNull(existsEmailDO)) {
            existsEmailDO = FrontUserExtraEmailDO.builder()
                    .password(this.encryptRawPassword(request.getPassword(), salt))
                    .salt(salt)
                    .username(request.getIdentity())
                    .verified(false)
                    .build();
            // 存储邮箱信息
            userExtraEmailService.save(existsEmailDO);
        } else {
            // 在未使用的邮箱基础上更新下密码信息，然后重新投入使用
            existsEmailDO.setSalt(salt);
            existsEmailDO.setVerified(false);
            existsEmailDO.setPassword(this.encryptRawPassword(request.getPassword(), salt));
            // 存储邮箱信息
            userExtraEmailService.updateById(existsEmailDO);
        }
        // 存储验证码记录
        EmailVerifyCodeDO emailVerifyCodeDO = emailVerifyCodeService.createVerifyCode(EmailBizTypeEnum.REGISTER_VERIFY, request.getIdentity());

        // 发送邮箱验证信息
        return emailService.sendForVerifyCode(request.getIdentity(), emailVerifyCodeDO.getVerifyCode());
    }

    @Override
    public UserInfoVO getLoginUserInfo(Integer extraInfoId) {
        FrontUserExtraEmailDO extraEmailDO = userExtraEmailService.getById(extraInfoId);

        // 根据注册类型+extraInfoId获取 当前邮箱绑定在了哪个用户上
        FrontUserExtraBindingDO bindingRelations = bindingService.findExtraBinding(FrontUserRegisterTypeEnum.EMAIL, extraInfoId);
        if (Objects.isNull(bindingRelations)) {
            throw new ServiceException(StrUtil.format("注册方式：{} 额外信息ID：{} 绑定关系不存在",
                    FrontUserRegisterTypeEnum.EMAIL.getDesc(), extraInfoId));
        }
        // 根据绑定关系查找基础用户信息
        FrontUserBaseDO baseUser = baseUserService.findUserInfoById(bindingRelations.getBaseUserId());
        if (Objects.isNull(baseUser)) {
            throw new ServiceException(StrUtil.format("基础用户不存在：{}", bindingRelations.getBaseUserId()));
        }
        // 封装基础用户信息并返回
        return UserInfoVO.builder().baseUserId(baseUser.getId())
                .description(baseUser.getDescription())
                .nickname(baseUser.getNickname())
                .email(extraEmailDO.getUsername())
                .avatarUrl("").build();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LoginInfoVO login(String username, String password) {
        // 校验邮箱登录权限
        EmailRegisterLoginConfig emailRegisterAccountConfig = EmailUtil.getRegisterAccountConfig();
        emailRegisterAccountConfig.checkLoginPermission(username);

        // 验证账号信息
        FrontUserExtraEmailDO emailDO = userExtraEmailService.getEmailAccount(username);
        if (Objects.isNull(emailDO) || BooleanUtil.isFalse(emailDO.getVerified())) {
            throw new ServiceException("邮箱未注册");
        }

        // 二次加密，验证账号密码
        String salt = emailDO.getSalt();
        String afterEncryptedPassword = this.encryptRawPassword(password, salt);
        if (!Objects.equals(afterEncryptedPassword, emailDO.getPassword())) {
            Integer baseUserId = 0;
            // 获取绑定的基础用户 id
            FrontUserExtraBindingDO userExtraBindingDO = bindingService.findExtraBinding(FrontUserRegisterTypeEnum.EMAIL, emailDO.getId());
            if (Objects.nonNull(userExtraBindingDO)) {
                FrontUserBaseDO userBaseDO = baseUserService.findUserInfoById(userExtraBindingDO.getBaseUserId());
                if (Objects.nonNull(userBaseDO)) {
                    baseUserId = userBaseDO.getId();
                }
            }

            // 记录登录失败日志
            loginLogService.loginFailed(FrontUserRegisterTypeEnum.EMAIL, emailDO.getId(), baseUserId, "账号或密码错误");
            throw new ServiceException("账号或密码错误");
        }

        // 获取登录用户信息
        UserInfoVO userInfo = this.getLoginUserInfo(emailDO.getId());

        // 执行登录
        StpUtil.login(userInfo.getBaseUserId(), SaLoginModel.create()
                .setExtra(FRONT_JWT_USERNAME, emailDO.getUsername())
                .setExtra(ApplicationConstant.FRONT_JWT_REGISTER_TYPE_CODE, FrontUserRegisterTypeEnum.EMAIL.getCode())
                .setExtra(FRONT_JWT_EXTRA_USER_ID, emailDO.getId()));

        // 记录登录日志
        loginLogService.loginSuccess(FrontUserRegisterTypeEnum.EMAIL, emailDO.getId(), userInfo.getBaseUserId());

        return LoginInfoVO.builder().token(StpUtil.getTokenValue()).baseUserId(userInfo.getBaseUserId()).build();
    }
}