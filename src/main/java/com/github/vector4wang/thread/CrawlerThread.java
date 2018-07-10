package com.github.vector4wang.thread;

import com.github.vector4wang.VWCrawler;
import com.github.vector4wang.annotation.CssSelector;
import com.github.vector4wang.model.PageRequest;
import com.github.vector4wang.proxy.ProxyBuilder;
import com.github.vector4wang.util.CrawlerUtil;
import com.github.vector4wang.util.JsoupUtil;
import com.github.vector4wang.util.SelectType;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * @author vector
 */
public class CrawlerThread implements Runnable {

	private Logger logger = LoggerFactory.getLogger(CrawlerThread.class.getName());
	private VWCrawler vwCrawler;

	public CrawlerThread(VWCrawler vwCrawler) {
		this.vwCrawler = vwCrawler;
	}

	@Override
	public void run() {
		/**
		 * 目标URL集合和种子页面URL集合 是否为空
		 */
		try {
			while (true) {
				String url  = vwCrawler.generateUrl();
				if (StringUtils.isEmpty(url)) {
					break;
				}
				process(url);
			}

//			if(vwCrawler.ge)

		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private void process(String url) {
		/**
		 * 自定义去重逻辑可以与数据库交互
		 */
		if (vwCrawler.getCrawlerService().isExist(url)) {
			return;
		}
		logger.info(Thread.currentThread().getName() + " 开始抓取 " + url);
		try {
			Document document = null;
			boolean isProxyInvalid = false;
			do {
				PageRequest pageRequest = new PageRequest();
				pageRequest.setUrl(url);
				pageRequest.setTimeout(vwCrawler.getTimeout());
				if (vwCrawler.getHeaderMap() != null && !vwCrawler.getHeaderMap().isEmpty()) {
					pageRequest.setHeader(vwCrawler.getHeaderMap());
				}
				if (vwCrawler.getProxys().size() > 0) {
					if (vwCrawler.getCurrentProxy() == null || isProxyInvalid) {
						vwCrawler.setCurrentProxy(JsoupUtil.getProxy(vwCrawler.getProxys(), ProxyBuilder.Type.RANDOM));
					}
					pageRequest.setProxy(vwCrawler.getCurrentProxy());
				}
				try {
					document = vwCrawler.getDownloader().downloadPage(pageRequest);

				} catch (ConnectException socketTimeoutException) {
					logger.warn("链接超时");
					isProxyInvalid = true;
					continue;
				} catch (SocketTimeoutException socketTimeoutException) {
					isProxyInvalid = true;
					continue;
				}

			} while (!vwCrawler.getCrawlerService().isContinue(document));

			if (document != null) {

				if (vwCrawler.getSeedsPageUrlRex().size() > 0) {
					/**
					 * 抽取满足正则的url
					 */
					Elements links = document.select("a[href]");
					if (links.size() > 0) {
						for (Element link : links) {
							String href = link.absUrl("href");
							for (String seedsPageUrlRex : vwCrawler.getSeedsPageUrlRex()) {
								if (CrawlerUtil.isMatch(seedsPageUrlRex, href)) {
									vwCrawler.addWaitCrawlerUrl(href);
								}
							}
						}

						for (Element link : links) {
							String href = link.absUrl("href");
							for (String targetUrlRex : vwCrawler.getTargetUrlRex()) {
								if (CrawlerUtil.isMatch(targetUrlRex, href)) {
									vwCrawler.addWaitCrawlerUrl(href);
								}
							}
						}
					}

				}
				vwCrawler.getCrawledUrls().add(url);

				/**
				 * 判断当前URL是否为target URL
				 */
				if (!vwCrawler.isTargetUrl(url)) {
					return;
				}


				Type[] type = ((ParameterizedType) vwCrawler.getCrawlerService().getClass().getGenericSuperclass())
						.getActualTypeArguments();
				Class aClass = (Class) type[0];
				Object pageVo = aClass.newInstance();

				/**
				 * 解析页面，封装obj
				 */
				Field[] declaredFields = pageVo.getClass().getDeclaredFields();
				if (declaredFields != null) {
					for (Field declaredField : declaredFields) {
						CssSelector annotation = declaredField.getAnnotation(CssSelector.class);
						if (annotation == null) {
							continue;
						}
						String selector = annotation.selector();
						SelectType selectType = annotation.resultType();
						if (selector == null || selector.length() <= 0) {
							continue;
						}
						String result;

						if (selectType == SelectType.HTML) {
							result = document.select(selector).toString();
						} else {
							result = document.select(selector).text();
						}
						declaredField.setAccessible(true);
						declaredField.set(pageVo, result);
					}
				}
				vwCrawler.getCrawlerService().parsePage(document, pageVo);
				vwCrawler.getCrawlerService().save(pageVo);
			}
		} catch (Exception e) {
			e.printStackTrace();
			if (e instanceof IOException) {
				logger.warn("请求地址发生错误");
			} else {
				logger.error(e.getMessage());
			}
		}
	}
}