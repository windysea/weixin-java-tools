package cn.binarywang.wx.miniapp.api;

import cn.binarywang.wx.miniapp.bean.message.WxMaInMessage;
import cn.binarywang.wx.miniapp.bean.message.WxMaOutMessage;
import me.chanjar.weixin.common.api.WxErrorExceptionHandler;
import me.chanjar.weixin.common.api.WxMessageDuplicateChecker;
import me.chanjar.weixin.common.api.WxMessageInMemoryDuplicateChecker;
import me.chanjar.weixin.common.session.InternalSession;
import me.chanjar.weixin.common.session.InternalSessionManager;
import me.chanjar.weixin.common.session.StandardSessionManager;
import me.chanjar.weixin.common.session.WxSessionManager;
import me.chanjar.weixin.common.util.LogExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * <pre>
 * 微信消息路由器，通过代码化的配置，把来自微信的消息交给handler处理
 *
 * 说明：
 * 1. 配置路由规则时要按照从细到粗的原则，否则可能消息可能会被提前处理
 * 2. 默认情况下消息只会被处理一次，除非使用 {@link WxMpMessageRouterRule#next()}
 * 3. 规则的结束必须用{@link WxMpMessageRouterRule#end()}或者{@link WxMpMessageRouterRule#next()}，否则不会生效
 *
 * 使用方法：
 * WxMpMessageRouter router = new WxMpMessageRouter();
 * router
 *   .rule()
 *       .msgDataFormat("MSG_TYPE").event("EVENT").eventKey("EVENT_KEY").content("CONTENT")
 *       .interceptor(interceptor, ...).handler(handler, ...)
 *   .end()
 *   .rule()
 *       // 另外一个匹配规则
 *   .end()
 * ;
 *
 * // 将WxXmlMessage交给消息路由器
 * router.route(message);
 *
 * </pre>
 *
 * @author Daniel Qian
 */
public class WxMpMessageRouter {

  private static final int DEFAULT_THREAD_POOL_SIZE = 100;
  protected final Logger log = LoggerFactory.getLogger(WxMpMessageRouter.class);
  private final List<WxMpMessageRouterRule> rules = new ArrayList<>();

  private final WxMaService wxMaService;

  private ExecutorService executorService;

  private WxMessageDuplicateChecker messageDuplicateChecker;

  private WxSessionManager sessionManager;

  private WxErrorExceptionHandler exceptionHandler;

  public WxMpMessageRouter(WxMaService wxMaService) {
    this.wxMaService = wxMaService;
    this.executorService = Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE);
    this.messageDuplicateChecker = new WxMessageInMemoryDuplicateChecker();
    this.sessionManager = new StandardSessionManager();
    this.exceptionHandler = new LogExceptionHandler();
  }

  /**
   * <pre>
   * 设置自定义的 {@link ExecutorService}
   * 如果不调用该方法，默认使用 Executors.newFixedThreadPool(100)
   * </pre>
   */
  public void setExecutorService(ExecutorService executorService) {
    this.executorService = executorService;
  }

  /**
   * <pre>
   * 设置自定义的 {@link me.chanjar.weixin.common.api.WxMessageDuplicateChecker}
   * 如果不调用该方法，默认使用 {@link me.chanjar.weixin.common.api.WxMessageInMemoryDuplicateChecker}
   * </pre>
   */
  public void setMessageDuplicateChecker(WxMessageDuplicateChecker messageDuplicateChecker) {
    this.messageDuplicateChecker = messageDuplicateChecker;
  }

  /**
   * <pre>
   * 设置自定义的{@link me.chanjar.weixin.common.session.WxSessionManager}
   * 如果不调用该方法，默认使用 {@link me.chanjar.weixin.common.session.StandardSessionManager}
   * </pre>
   */
  public void setSessionManager(WxSessionManager sessionManager) {
    this.sessionManager = sessionManager;
  }

  /**
   * <pre>
   * 设置自定义的{@link me.chanjar.weixin.common.api.WxErrorExceptionHandler}
   * 如果不调用该方法，默认使用 {@link me.chanjar.weixin.common.util.LogExceptionHandler}
   * </pre>
   */
  public void setExceptionHandler(WxErrorExceptionHandler exceptionHandler) {
    this.exceptionHandler = exceptionHandler;
  }

  List<WxMpMessageRouterRule> getRules() {
    return this.rules;
  }

  /**
   * 开始一个新的Route规则
   */
  public WxMpMessageRouterRule rule() {
    return new WxMpMessageRouterRule(this);
  }

  /**
   * 处理微信消息
   */
  public WxMaOutMessage route(final WxMaInMessage wxMessage, final Map<String, Object> context) {
    final List<WxMpMessageRouterRule> matchRules = new ArrayList<>();
    // 收集匹配的规则
    for (final WxMpMessageRouterRule rule : this.rules) {
      if (rule.test(wxMessage)) {
        matchRules.add(rule);
        if (!rule.isReEnter()) {
          break;
        }
      }
    }

    if (matchRules.size() == 0) {
      return null;
    }

    final List<Future<?>> futures = new ArrayList<>();
    for (final WxMpMessageRouterRule rule : matchRules) {
      // 返回最后一个非异步的rule的执行结果
      if (rule.isAsync()) {
        futures.add(
          this.executorService.submit(new Runnable() {
            @Override
            public void run() {
              rule.service(wxMessage, context, WxMpMessageRouter.this.wxMaService, WxMpMessageRouter.this.sessionManager, WxMpMessageRouter.this.exceptionHandler);
            }
          })
        );
      } else {
        rule.service(wxMessage, context, this.wxMaService, this.sessionManager, this.exceptionHandler);
        // 在同步操作结束，session访问结束
        this.log.debug("End session access: async=false, sessionId={}", wxMessage.getFromUser());
        sessionEndAccess(wxMessage);
      }
    }

    if (futures.size() > 0) {
      this.executorService.submit(new Runnable() {
        @Override
        public void run() {
          for (Future<?> future : futures) {
            try {
              future.get();
              WxMpMessageRouter.this.log.debug("End session access: async=true, sessionId={}", wxMessage.getFromUser());
              // 异步操作结束，session访问结束
              sessionEndAccess(wxMessage);
            } catch (InterruptedException | ExecutionException e) {
              WxMpMessageRouter.this.log.error("Error happened when wait task finish", e);
            }
          }
        }
      });
    }
    return null;
  }

  public WxMaOutMessage route(final WxMaInMessage wxMessage) {
    return this.route(wxMessage, new HashMap<String, Object>());
  }

  /**
   * 对session的访问结束
   */
  protected void sessionEndAccess(WxMaInMessage wxMessage) {

    InternalSession session = ((InternalSessionManager) this.sessionManager).findSession(wxMessage.getFromUser());
    if (session != null) {
      session.endAccess();
    }

  }
}
