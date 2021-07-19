package com.molicloud.mqr.framework.util;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.molicloud.mqr.framework.initialize.PluginSettingInitialize;
import com.molicloud.mqr.plugin.core.HoldInfo;
import com.molicloud.mqr.plugin.core.PluginContextHolder;
import com.molicloud.mqr.plugin.core.PluginParam;
import com.molicloud.mqr.plugin.core.PluginResult;
import com.molicloud.mqr.plugin.core.define.PluginSettingDef;
import com.molicloud.mqr.plugin.core.enums.ExecuteTriggerEnum;
import com.molicloud.mqr.plugin.core.enums.KeywordTypeEnum;
import com.molicloud.mqr.plugin.core.enums.RobotEventEnum;
import com.molicloud.mqr.framework.PluginExecutorRegistrar;
import com.molicloud.mqr.framework.common.PluginHook;
import com.molicloud.mqr.framework.listener.event.PluginResultEvent;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 插件工具类
 *
 * @author feitao yyimba@qq.com
 * @since 2020/11/11 4:18 下午
 */
@Slf4j
@UtilityClass
public class PluginUtil {

    /**
     * 执行插件，如果执行成功，则把结果set到事件中
     *
     * @param pluginResultEvent
     * @return
     */
    public boolean executePlugin(PluginResultEvent pluginResultEvent) {
        // 插件入参
        PluginParam pluginParam = pluginResultEvent.getPluginParam();

        // 处理监听所有消息的插件钩子
        List<PluginHook> listeningAllMessagePluginHookList = PluginExecutorRegistrar.getListeningAllMessagePluginHookList(pluginParam.getRobotEventEnum());
        if (CollUtil.isNotEmpty(listeningAllMessagePluginHookList)) {
            pluginParam.setExecuteTriggerEnum(ExecuteTriggerEnum.LISTENING_ALL_MESSAGE);
            if (executeAllPluginHook(listeningAllMessagePluginHookList, pluginResultEvent)) {
                return true;
            }
        }

        // 处理消息发送者持有的插件钩子
        if (executeHoldPluginHook(pluginResultEvent)) {
            return true;
        }

        // 如果消息为字符串，则处理常规的插件钩子，通过关键字触发
        if (pluginParam.getData() instanceof String) {
            List<PluginHook> normalPluginHookList = PluginExecutorRegistrar.getNormalPluginHookList(pluginParam.getRobotEventEnum());
            if (CollUtil.isNotEmpty(normalPluginHookList)) {
                // 记录钩子关键字
                Map<String, String> hookKeywordMap = new HashMap<>();
                // 过滤关键字
                List<PluginHook> keywordPluginHookList = normalPluginHookList.stream()
                        .filter(pluginHook -> {
                            String keyword = "";
                            String message = String.valueOf(pluginParam.getData());
                            if (CollUtil.isNotEmpty(pluginHook.getEqualsKeywords())) {
                                keyword = getKeyword(pluginHook.getEqualsKeywords(), message, KeywordTypeEnum.EQUALS);
                            }
                            if (StrUtil.isEmpty(keyword) && CollUtil.isNotEmpty(pluginHook.getStartsKeywords())) {
                                keyword = getKeyword(pluginHook.getStartsKeywords(), message, KeywordTypeEnum.STARTS);
                            }
                            if (StrUtil.isEmpty(keyword) && CollUtil.isNotEmpty(pluginHook.getEndsKeywords())) {
                                keyword = getKeyword(pluginHook.getEndsKeywords(), message, KeywordTypeEnum.ENDS);
                            }
                            if (StrUtil.isEmpty(keyword) && CollUtil.isNotEmpty(pluginHook.getContainsKeywords())) {
                                keyword = getKeyword(pluginHook.getContainsKeywords(), message, KeywordTypeEnum.CONTAINS);
                            }
                            if (StrUtil.isNotEmpty(keyword)) {
                                hookKeywordMap.put(pluginHook.getName(), keyword);
                                return true;
                            }
                            return false;
                        }).collect(Collectors.toList());
                if (CollUtil.isNotEmpty(keywordPluginHookList)) {
                    pluginParam.setExecuteTriggerEnum(ExecuteTriggerEnum.KEYWORD);
                    if (executeAllPluginHook(keywordPluginHookList, pluginResultEvent, hookKeywordMap)) {
                        return true;
                    }
                }
            }
        }

        // 处理默认的插件钩子
        List<PluginHook> defaultPluginHookList = PluginExecutorRegistrar.getDefaultPluginHookList(pluginParam.getRobotEventEnum());
        if (CollUtil.isNotEmpty(defaultPluginHookList)) {
            pluginParam.setExecuteTriggerEnum(ExecuteTriggerEnum.DEFAULTED);
            if (executeAllPluginHook(defaultPluginHookList, pluginResultEvent)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 获取插件被触发的关键字/词
     *
     * @param keywords
     * @param message
     * @return
     */
    private String getKeyword(Set<String> keywords, String message, KeywordTypeEnum keywordTypeEnum) {
        Optional<String> keywordOption = keywords.stream().filter(keyword -> {
            boolean result = false;
            switch (keywordTypeEnum) {
                case EQUALS:
                    result = keyword.equalsIgnoreCase(message);
                    break;
                case STARTS:
                    result = StrUtil.startWith(message, keyword, true);
                    break;
                case ENDS:
                    result = StrUtil.endWith(message, keyword, true);
                    break;
                case CONTAINS:
                    result = StrUtil.containsIgnoreCase(message, keyword);
                    break;
                default:
                    break;
            }
            return result;
        }).findAny();
        return keywordOption.orElse("");
    }

    /**
     * 执行持有的插件钩子（如果有的话）
     *
     * @param pluginResultEvent
     * @return
     */
    private boolean executeHoldPluginHook(PluginResultEvent pluginResultEvent) {
        // 插件持有信息
        HoldInfo holdInfo = null;
        // 插件入参
        PluginParam pluginParam = pluginResultEvent.getPluginParam();
        // 判断群消息和好友消息的人是否持有对应的插件钩子
        if (RobotEventEnum.GROUP_MSG.equals(pluginParam.getRobotEventEnum())
                && PluginHookUtil.groupMemberHasPluginHook(pluginParam.getTo(), pluginParam.getFrom())) {
            holdInfo = PluginHookUtil.getGroupMemberHoldInfo(pluginParam.getTo(), pluginParam.getFrom());
        } else if (RobotEventEnum.FRIEND_MSG.equals(pluginParam.getRobotEventEnum())
                && PluginHookUtil.friendHasPluginHook(pluginParam.getFrom())) {
            holdInfo = PluginHookUtil.getFriendHoldInfo(pluginParam.getFrom());
        }

        if (holdInfo != null) {
            // 通过持有的插件钩子来执行
            pluginParam.setExecuteTriggerEnum(ExecuteTriggerEnum.HOLD);
            pluginParam.setHoldMessage(holdInfo.getMessage());
            PluginResult pluginResult = executePluginHook(holdInfo.getHookName(), pluginParam);
            if (pluginResult != null && pluginResult.getProcessed()) {
                pluginResultEvent.setPluginHookName(holdInfo.getHookName());
                pluginResultEvent.setPluginResult(pluginResult);
                return true;
            }
        }
        return false;
    }

    /**
     * 按照优先级执行所有插件
     *
     * @param pluginResultEvent
     * @return
     */
    private boolean executeAllPluginHook(List<PluginHook> pluginHookList, PluginResultEvent pluginResultEvent) {
        return executeAllPluginHook(pluginHookList, pluginResultEvent, null);
    }

    /**
     * 按照优先级执行所有插件
     *
     * @param pluginResultEvent
     * @return
     */
    private boolean executeAllPluginHook(List<PluginHook> pluginHookList, PluginResultEvent pluginResultEvent, Map<String, String> hookKeywordMap) {
        // 插件入参
        PluginParam pluginParam = pluginResultEvent.getPluginParam();
        for (PluginHook pluginHook : pluginHookList) {
            if (pluginHook.getRobotEvents().contains(pluginParam.getRobotEventEnum())) {
                // 如果钩子关键字不为空，放到插件入参中
                if (MapUtil.isNotEmpty(hookKeywordMap) && StrUtil.isNotBlank(hookKeywordMap.get(pluginHook.getName()))) {
                    pluginParam.setKeyword(hookKeywordMap.get(pluginHook.getName()));
                }
                PluginResult pluginResult = executePluginHook(pluginHook, pluginParam);
                if (pluginResult != null && pluginResult.getProcessed()) {
                    pluginResultEvent.setPluginHookName(pluginHook.getName());
                    pluginResultEvent.setPluginResult(pluginResult);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 插件执行
     *
     * @param pluginHook
     * @param pluginParam
     * @return
     */
    private PluginResult executePluginHook(PluginHook pluginHook, PluginParam pluginParam) {
        PluginResult pluginResult = null;
        try {
            // 线程上下文中存入插件配置信息
            String settingValue = PluginSettingInitialize.getPluginSettingValue(pluginHook.getName());
            PluginSettingDef pluginSettingDef = new PluginSettingDef();
            pluginSettingDef.setName(pluginHook.getName());
            pluginSettingDef.setSettingValue(settingValue);
            PluginContextHolder.setSetting(pluginSettingDef);
            // 执行插件钩子的方法
            pluginResult = pluginHook.getPluginMethod().execute(pluginParam);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            PluginContextHolder.clear();
        }
        return pluginResult;
    }

    /**
     * 插件执行（根据插件钩子名来获取钩子执行）
     *
     * @param pluginHookName
     * @param pluginParam
     * @return
     */
    private PluginResult executePluginHook(String pluginHookName, PluginParam pluginParam) {
        PluginHook pluginHook = PluginExecutorRegistrar.getPluginHookByName(pluginHookName);
        if (pluginHook == null) {
            return null;
        }
        return executePluginHook(pluginHook, pluginParam);
    }
}
