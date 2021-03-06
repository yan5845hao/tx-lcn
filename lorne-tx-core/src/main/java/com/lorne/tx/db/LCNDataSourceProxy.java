package com.lorne.tx.db;

import com.lorne.core.framework.utils.task.Task;
import com.lorne.tx.bean.TxTransactionLocal;
import com.lorne.tx.compensate.service.impl.CompensateServiceImpl;
import com.lorne.tx.db.service.DataSourceService;
import org.apache.commons.lang.StringUtils;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;


/**
 * create by lorne on 2017/7/29
 */

public class LCNDataSourceProxy implements DataSource {


    protected interface ISubNowConnection {

        void close(AbstractConnection connection);

    }

    private org.slf4j.Logger logger = LoggerFactory.getLogger(LCNDataSourceProxy.class);


    private Map<String, AbstractConnection> pools = new ConcurrentHashMap<>();

    //private Executor threadPool = Executors.newFixedThreadPool(ThreadPoolSizeHelper.getInstance().getInThreadSize());

    @Autowired
    private DataSourceService dataSourceService;


    private DataSource dataSource;

    //default size
    private volatile int maxCount = 5;

    //default time (seconds)
    private int maxWaitTime = 30;

    private volatile int nowCount = 0;

    // not thread
    private ISubNowConnection subNowCount = new ISubNowConnection() {

        @Override
        public void close(AbstractConnection connection) {
            Task waitTask = connection.getWaitTask();
            if (waitTask != null) {
                if (!waitTask.isRemove()) {
                    waitTask.remove();
                }
            }

            pools.remove(connection.getGroupId());
            System.out.println("pools-size->" + pools.size());
            nowCount--;
        }
    };


    private Connection loadConnection(TxTransactionLocal txTransactionLocal, Connection connection) throws SQLException {
        AbstractConnection old = pools.get(txTransactionLocal.getGroupId());
        if (old != null) {
            old.setHasIsGroup(true);
            txTransactionLocal.setHasIsGroup(true);
            TxTransactionLocal.setCurrent(txTransactionLocal);
            logger.info("get old connection ->" + txTransactionLocal.getGroupId());
            return old;
        }
        if (nowCount == maxCount) {
            logger.info("initLCNConnection max count ...");
            for (int i = 0; i < maxWaitTime; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (nowCount < maxCount) {
                    return createLcnConnection(connection, txTransactionLocal);
                }
            }
        } else if (nowCount < maxCount) {
            return createLcnConnection(connection, txTransactionLocal);
        } else {
            throw new SQLException("connection was overload");
        }
        return connection;
    }

    private Connection createLcnConnection(Connection connection, TxTransactionLocal txTransactionLocal) {
        nowCount++;
        LCNConnection lcn = new LCNConnection(connection, dataSourceService, txTransactionLocal, subNowCount);
        pools.put(txTransactionLocal.getGroupId(), lcn);
        logger.info("get new connection ->" + txTransactionLocal.getGroupId());
        return lcn;
    }

    private Connection initLCNConnection(Connection connection) throws SQLException {
        Connection lcnConnection = connection;
        TxTransactionLocal txTransactionLocal = TxTransactionLocal.current();

        if (txTransactionLocal != null
            && StringUtils.isNotEmpty(txTransactionLocal.getGroupId())) {

            //logger.info("initLCNConnection - lcn ->"+connection);

            if (CompensateServiceImpl.COMPENSATE_KEY.equals(txTransactionLocal.getGroupId())) {
                lcnConnection = loadConnection(txTransactionLocal, connection);
            } else if (!txTransactionLocal.isHasStart()) {
                lcnConnection = loadConnection(txTransactionLocal, connection);
            }

        }
        //logger.info("initLCNConnection - end ->"+connection);
        return lcnConnection;
    }

    public void setMaxWaitTime(int maxWaitTime) {
        this.maxWaitTime = maxWaitTime;
    }

    public void setMaxCount(int maxCount) {
        this.maxCount = maxCount;
    }


    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }


    @Override
    public Connection getConnection() throws SQLException {
        return initLCNConnection(dataSource.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return initLCNConnection(dataSource.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return dataSource.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        dataSource.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        dataSource.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return dataSource.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return dataSource.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return dataSource.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return dataSource.isWrapperFor(iface);
    }
}
