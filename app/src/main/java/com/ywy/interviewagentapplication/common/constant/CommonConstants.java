package com.ywy.interviewagentapplication.common.constant;

/**
 * 通用常量定义
 * 设计原因：将项目中重复使用的魔法数字集中管理，避免散落各处
 * 被依赖方：Result（使用 StatusCode）、各 Service（使用 Pagination）、
 *           面试模块（使用 InterviewDefaults）
 */
public final class CommonConstants {

    // 私有构造器：禁止实例化（纯常量工具类）
    private CommonConstants() {}

    /**
     * 业务状态码：表示业务代码正常执行产出的结果
     * 如果业务出现异常，则业务的异常状态码由 ErrorCode.java 定义
     */
    public static final class StatusCode {
        /** 请求成功 */
        public static final int SUCCESS = 200;
        /** 请求参数错误 */
        public static final int BAD_REQUEST = 400;
        /** 未授权（未登录或 Token 无效） */
        public static final int UNAUTHORIZED = 401;
        /** 禁止访问（已登录但权限不足） */
        public static final int FORBIDDEN = 403;
        /** 资源不存在 */
        public static final int NOT_FOUND = 404;
        /** 服务器内部错误 */
        public static final int SERVER_ERROR = 500;

        private StatusCode() {}
    }

    /**
     * 分页查询默认值
     */
    public static final class Pagination {
        /** 默认页码（第一页） */
        public static final int DEFAULT_PAGE = 1;
        /** 默认每页大小 */
        public static final int DEFAULT_SIZE = 20;
        /** 单页最大记录数（防止恶意查询拖垮数据库） */
        public static final int MAX_SIZE = 100;

        private Pagination() {}
    }

    /**
     * 面试模块默认值
     */
    public static final class InterviewDefaults {
        /** 默认技能方向：Java 后端 */
        public static final String SKILL_ID = "java-backend";
        /** 默认难度：中等 */
        public static final String DIFFICULTY = "mid";

        private InterviewDefaults() {}
    }
}
