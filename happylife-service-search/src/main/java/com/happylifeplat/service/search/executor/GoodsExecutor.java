package com.happylifeplat.service.search.executor;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.model.ConfigChangeEvent;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfig;
import com.ctrip.framework.apollo.spring.annotation.ApolloConfigChangeListener;
import com.happylifeplat.facade.search.enums.EsConfigTypeEnum;
import com.happylifeplat.plugin.mybatis.pager.PageParameter;
import com.happylifeplat.service.search.constant.ConstantSearch;
import com.happylifeplat.service.search.entity.EsConfig;
import com.happylifeplat.service.search.entity.GoodsEs;
import com.happylifeplat.service.search.entity.HandlerEntity;
import com.happylifeplat.service.search.entity.JobInfo;
import com.happylifeplat.service.search.entity.ProviderRegionEs;
import com.happylifeplat.service.search.executor.handler.ConcurrentHandler;
import com.happylifeplat.service.search.executor.handler.GoodsHandler;
import com.happylifeplat.service.search.helper.LogUtil;
import com.happylifeplat.service.search.mapper.EsConfigMapper;
import com.happylifeplat.service.search.mapper.GoodsEsMapper;
import com.happylifeplat.service.search.mapper.GoodsImageEsMapper;
import com.happylifeplat.service.search.mapper.GoodsTypeEsMapper;
import com.happylifeplat.service.search.mapper.ProviderEsMapper;
import com.happylifeplat.service.search.mapper.ProviderRegionEsMapper;
import com.happylifeplat.service.search.query.GoodsPage;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


/**
 * <p>Description: .</p>
 * <p>Company: 深圳市旺生活互联网科技有限公司</p>
 * <p>Copyright: 2015-2017 happylifeplat.com All Rights Reserved</p>
 * 商品处理
 *
 * @author yu.xiao@happylifeplat.com
 * @version 1.0
 * @date 2017/3/29 18:15
 * @since JDK 1.8
 */
@Component
public class GoodsExecutor implements ElasticSearchExecutor {

    /**
     * logger
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(GoodsExecutor.class);

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Autowired(required = false)
    private GoodsEsMapper goodsEsMapper;

    @Autowired(required = false)
    private ProviderRegionEsMapper providerRegionEsMapper;

    @Autowired(required = false)
    private GoodsImageEsMapper goodsImageEsMapper;

    @Autowired(required = false)
    private ProviderEsMapper providerEsMapper;

    @Autowired(required = false)
    private GoodsTypeEsMapper goodsTypeEsMapper;

    @Autowired(required = false)
    private EsConfigMapper esConfigMapper;

    @Autowired
    private ConcurrentHandler concurrentHandler;

    @ApolloConfig
    private Config config;

    @Value("${goods.pageSize}")
    private int goodsPageSize;


    @Override
    public void execute(JobInfo jobInfo) {
        LogUtil.info(LOGGER, () -> "开始建立商品索引,jobInfo = [" + jobInfo.toString() + "]");
        final String index = jobInfo.getIndex();
        final String type = jobInfo.getType();
        final String updateTime = getLastTime();
        int currentPage = 1;
        PageParameter pageParameter = new PageParameter();
        pageParameter.setPageSize(goodsPageSize);
        GoodsPage goodsPage = new GoodsPage();
        goodsPage.setUpdateTime(updateTime);
        while (true) {
            pageParameter.setCurrentPage(currentPage);
            goodsPage.setPage(pageParameter);
            final List<GoodsEs> goodsEsList = goodsEsMapper.listPage(goodsPage);
            if (CollectionUtils.isEmpty(goodsEsList)) {
                break;
            } else {
                //设置商品对应的服务区域,类型名称，供应商名称，图片信息
                final List<GoodsEs> esList = goodsEsList.parallelStream().filter(Objects::nonNull)
                        .map(goodsEs -> {
                            try {
                                //设置主图
                                final String primaryImageUrl = goodsImageEsMapper.findPrimaryImageUrlByGoodsId(goodsEs.getId());
                                goodsEs.setThumbnail(primaryImageUrl);
                                //设置供应商名称
                                final String providerName = providerEsMapper.getNameById(goodsEs.getProviderId());
                                goodsEs.setProviderName(providerName);
                                //设置分类名称
                                final String goodsTypeName = goodsTypeEsMapper.getNameById(goodsEs.getGoodsTypeId());
                                goodsEs.setGoodsTypeName(goodsTypeName);
                                final List<ProviderRegionEs> providerRegionEsList =
                                        providerRegionEsMapper.listByProviderId(goodsEs.getProviderId());
                                goodsEs.setRegions(providerRegionEsList);
                            } catch (Exception e) {
                                LogUtil.error(LOGGER,"查询分类，供应商，区域信息异常:{}",e::getLocalizedMessage);
                            }
                            return goodsEs;
                        }).collect(Collectors.toList());
                /**
                 * 封装成handlerEntity 异步提交
                 */
                CompletableFuture.supplyAsync(() -> {
                    HandlerEntity<GoodsEs> handlerEntity = new HandlerEntity<>();
                    handlerEntity.setType(EsConfigTypeEnum.GOODS.getCode());
                    handlerEntity.setHandler(GoodsHandler.class);
                    handlerEntity.setIndex(index);
                    handlerEntity.setIndexType(type);
                    handlerEntity.setData(esList);
                    return handlerEntity;
                }).thenAccept(concurrentHandler::submit);
            }
            LogUtil.info(LOGGER, "当前处理页数为：{}", currentPage);
            //分页处理
            pageParameter = goodsPage.getPage();
            final int totalPage = pageParameter.getTotalPage();
            if (totalPage == currentPage) {
                break;
            }
            currentPage++;
        }
        //更新处理时间
        updateLastTime();
    }

    /**
     * 更新最后操作时间
     */
    private void updateLastTime() {
        final EsConfig byType = getByType(EsConfigTypeEnum.GOODS.getCode());
        byType.setLastTime(new Date());
        esConfigMapper.update(byType);

    }

    /**
     * 获取上一次操作时间
     *
     * @return byType.getLastTime()
     */
    private String getLastTime() {
        final EsConfig byType = getByType(EsConfigTypeEnum.GOODS.getCode());
        if (Objects.nonNull(byType)) {
            return DATE_FORMAT.format(byType.getLastTime());
        }
        return ConstantSearch.DEFAULT_LAST_TIME;
    }

    private EsConfig getByType(int type) {
        return esConfigMapper.getByType(type);
    }


    @ApolloConfigChangeListener("application")
    private void pageSizeChangeHandler(ConfigChangeEvent changeEvent) {
        LogUtil.info(LOGGER, () -> " " + "changeEvent = [" + changeEvent + "]");
        if (changeEvent.isChanged("goods.pageSize")) {
            goodsPageSize = config.getIntProperty("goods.pageSize", goodsPageSize);
            LogUtil.info(LOGGER, "goods.pageSize {}", () -> goodsPageSize);
        }
    }
}