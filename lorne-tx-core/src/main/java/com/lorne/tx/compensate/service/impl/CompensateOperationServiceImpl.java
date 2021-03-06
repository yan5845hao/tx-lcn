package com.lorne.tx.compensate.service.impl;

import com.lorne.core.framework.utils.KidUtils;
import com.lorne.core.framework.utils.config.ConfigUtils;
import com.lorne.core.framework.utils.http.HttpUtils;
import com.lorne.tx.bean.TxTransactionCompensate;
import com.lorne.tx.compensate.model.QueueMsg;
import com.lorne.tx.compensate.model.TransactionInvocation;
import com.lorne.tx.compensate.model.TransactionRecover;
import com.lorne.tx.compensate.repository.TransactionRecoverRepository;
import com.lorne.tx.compensate.service.CompensateOperationService;
import com.lorne.tx.exception.TransactionRuntimeException;
import com.lorne.tx.mq.service.NettyService;
import com.lorne.tx.utils.MethodUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by lorne on 2017/7/12.
 */
@Service
public class CompensateOperationServiceImpl implements CompensateOperationService {

    @Autowired
    private ApplicationContext applicationContext;

    private Logger logger = LoggerFactory.getLogger(CompensateOperationServiceImpl.class);

    private TransactionRecoverRepository recoverRepository;

    private String url;
    private String prefix;

    /**
     * 保存数据消息队列
     */
    private BlockingQueue<QueueMsg> queueList;

    /**
     * 是否可以优雅关闭 程序可配置
     */
    private boolean hasGracefulClose = false;


    @Autowired
    private NettyService nettyService;


    private static final int max_size = 20;
    private final Executor threadPools = Executors.newFixedThreadPool(max_size);


    public CompensateOperationServiceImpl() {
        url = ConfigUtils.getString("tx.properties", "url");
        prefix = ConfigUtils.getString("tx.properties", "compensate.prefix");
        int state = 0;
        try {
            state = ConfigUtils.getInt("tx.properties", "graceful.close");
        } catch (Exception e) {
            state = 0;
        }
        if (state == 1) {
            hasGracefulClose = true;
        }
        queueList = new LinkedBlockingDeque<>();
    }

    @Override
    public void setTransactionRecover(TransactionRecoverRepository recoverRepository) {
        this.recoverRepository = recoverRepository;
    }

    @Override
    public List<TransactionRecover> findAll(int state) {
        return recoverRepository.findAll(state);
    }

    @Override
    public void execute(TransactionRecover data) {
        if (data != null) {
            TransactionInvocation invocation = data.getInvocation();
            if (invocation != null) {
                //通知TM
                String murl = url + "GroupState?groupId=" + data.getGroupId();
                logger.info("获取补偿事务状态url->" + murl);
                String groupState = HttpUtils.get(murl);
                logger.info("获取补偿事务状态TM->" + groupState);

                if (null == groupState) {
                    return;
                }

                if (groupState.contains("true")) {
                    TxTransactionCompensate compensate = new TxTransactionCompensate();
                    TxTransactionCompensate.setCurrent(compensate);
                    boolean isOk = MethodUtils.invoke(applicationContext, invocation);
                    if (isOk) {
                        String notifyGroup = HttpUtils.get(url + "Group?groupId=" + data.getGroupId() + "&taskId=" + data.getTaskId());
                        logger.info("补偿事务通知TM->" + notifyGroup);
                        delete(data.getId());
                    } else {
                        updateRetriedCount(data.getId(), data.getRetriedCount() + 1);
                    }
                } else {
                    //回滚操作直接清理事务补偿日志
                    delete(data.getId());
                }
            }
        }
    }

    @Override
    public String save(TransactionInvocation transactionInvocation, String groupId, String taskId) {
        TransactionRecover recover = new TransactionRecover();
        recover.setGroupId(groupId);
        recover.setTaskId(taskId);
        recover.setId(KidUtils.generateShortUuid());
        recover.setInvocation(transactionInvocation);
        try {
            QueueMsg msg = new QueueMsg();
            msg.setRecover(recover);
            msg.setType(1);
            if (hasGracefulClose) {
                queueList.put(msg);
            } else {
                recoverRepository.create(recover);
            }
            return recover.getId();
        } catch (Exception e) {
            throw new TransactionRuntimeException("补偿数据库插入失败.");
        }
    }

    @Override
    public boolean updateRetriedCount(String id, int retriedCount) {
        return recoverRepository.update(id, new Date(), 0, retriedCount) > 0;
    }

    @Override
    public boolean delete(String id) {
        try {
            QueueMsg msg = new QueueMsg();
            msg.setId(id);
            msg.setType(0);

            if (hasGracefulClose) {
                queueList.put(msg);
            } else {
                recoverRepository.remove(id);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getTableName(String modelName) {
        Pattern pattern = Pattern.compile("[^a-z0-9A-Z]");
        Matcher matcher = pattern.matcher(modelName);
        return matcher.replaceAll("_");
    }


    @Override
    public void init(String modelName) {

        String tableName = "lcn_tx_" + prefix + "_" + getTableName(modelName);

        recoverRepository.init(tableName);

        if (hasGracefulClose) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < max_size; i++) {
                        threadPools.execute(new Runnable() {
                            @Override
                            public void run() {
                                while (true) {
                                    try {
                                        QueueMsg msg = queueList.take();
                                        if (msg != null) {
                                            if (msg.getType() == 1) {
                                                recoverRepository.create(msg.getRecover());
                                            } else {
                                                int rs = recoverRepository.remove(msg.getId());
                                                if (rs == 0) {
                                                    delete(msg.getId());
                                                }
                                            }
                                        }
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }

                                }
                            }
                        });
                    }
                }
            };

            Thread thread = new Thread(runnable);
            thread.start();


            /**关闭时需要操作的业务**/

            Thread shutdownQueueList = new Thread(runnable);
            Runtime.getRuntime().addShutdownHook(shutdownQueueList);


            Thread shutdownNetty = new Thread(new Runnable() {
                @Override
                public void run() {
                    nettyService.close();
                }
            });
            Runtime.getRuntime().addShutdownHook(shutdownNetty);


        }

    }
}
