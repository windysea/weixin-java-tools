package cn.binarywang.wx.miniapp.builder.kefu;

import cn.binarywang.wx.miniapp.bean.kefu.WxMaKefuMessage;
import me.chanjar.weixin.common.api.WxConsts;

/**
 * 视频消息builder
 * <pre>
 * 用法: WxMaKefuMessage m = WxMaKefuMessage.VOICE()
 *                              .mediaId(...)
 *                              .title(...)
 *                              .thumbMediaId(..)
 *                              .description(..)
 *                              .toUser(...)
 *                              .build();
 * </pre>
 *
 * @author chanjarster
 */
public final class VideoBuilder extends BaseBuilder<VideoBuilder> {
  private String mediaId;
  private String title;
  private String description;
  private String thumbMediaId;

  public VideoBuilder() {
    this.msgType = WxConsts.CUSTOM_MSG_VIDEO;
  }

  public VideoBuilder mediaId(String mediaId) {
    this.mediaId = mediaId;
    return this;
  }

  public VideoBuilder title(String title) {
    this.title = title;
    return this;
  }

  public VideoBuilder description(String description) {
    this.description = description;
    return this;
  }

  public VideoBuilder thumbMediaId(String thumb_media_id) {
    this.thumbMediaId = thumb_media_id;
    return this;
  }

  @Override
  public WxMaKefuMessage build() {
    WxMaKefuMessage m = super.build();
    m.setMediaId(this.mediaId);
    m.setTitle(this.title);
    m.setDescription(this.description);
    m.setThumbMediaId(this.thumbMediaId);
    return m;
  }
}
