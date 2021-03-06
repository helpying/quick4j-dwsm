package com.github.quick4j.core.web.http.distributed.session.manager;

import com.github.quick4j.core.web.http.distributed.session.SessionIDManager;
import com.github.quick4j.core.web.http.distributed.session.SessionManager;
import com.github.quick4j.core.web.http.distributed.session.SessionStorage;
import com.github.quick4j.core.web.http.distributed.session.manager.task.ClearInvalidSessionScheduledTask;
import com.github.quick4j.core.web.http.distributed.session.session.DistributedHttpSession;
import com.github.quick4j.core.web.http.distributed.session.session.SessionEventType;
import com.github.quick4j.core.web.http.distributed.session.session.metadata.SessionMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionEvent;
import javax.servlet.http.HttpSessionListener;
import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author zhaojh.
 */
public abstract class AbstractSessionManager implements SessionManager, InitializingBean, DisposableBean{
    private final Logger logger = LoggerFactory.getLogger(AbstractSessionManager.class);

    private int maxInactiveInterval = 60 * 30;
    private ServletContext servletContext;

    private List<EventListener> listeners = new ArrayList<EventListener>();
    private Map<String, HttpSession> localSessionContainer = new ConcurrentHashMap<String, HttpSession>();
    @Autowired
    private SessionStorage sessionStorage;
    @Autowired
    private SessionIDManager sessionIdManager;
    private ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1);

    protected abstract HttpSession newHttpSession(String id, int maxInactiveInterval);
    protected abstract HttpSession newHttpSession(SessionMetaData metaData);

    @Override
    public void destroy() throws Exception {
        stop();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        start();
    }

    public void setSessionStorage(SessionStorage sessionStorage) {
        this.sessionStorage = sessionStorage;
    }

    public void setSessionIdManager(SessionIDManager sessionIdManager) {
        this.sessionIdManager = sessionIdManager;
    }

    public void setSessionTimeout(int maxInactiveInterval) {
        this.maxInactiveInterval = 60 * maxInactiveInterval;
    }

    public void setEventListeners(List<EventListener> listeners){
        this.listeners = listeners;
    }

    @Override
    public List<EventListener> getEventListeners() {
        return listeners;
    }

    @Override
    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }

    @Override
    public HttpSession newHttpSession(HttpServletRequest request) {
        String id = newSessionId(request);
        HttpSession session = newHttpSession(id, maxInactiveInterval);
        fireSessionCreatedEvent(session);
        addSessionToLocal(session);
        return session;
    }

    @Override
    public HttpSession getHttpSession(String id) {
        HttpSession session = getSessionFromLocalOrStorage(id);
        addSessionToLocal(session);
        return session;
    }

    @Override
    public void removeHttpSession(HttpSession session) {
        fireSessionDestroyedEvent(session);
        removeSessionFromStorage(session);
        removeSessionFromLocal(session);
    }

    @Override
    public boolean isValid(HttpSession session) {
        if(null == session) return false;

        return ((DistributedHttpSession)session).isValid();
    }

    @Override
    public void start() {
        logger.info("启动Session Manager.");
        sessionIdManager.start();
        sessionStorage.start();
        startClearInvalidSessionScheduledTask();
    }

    @Override
    public void stop() {
        scheduledExecutorService.shutdown();
        sessionIdManager.stop();
        sessionStorage.stop();
    }

    @Override
    public SessionStorage getSessionStorage() {
        return sessionStorage;
    }

    protected ServletContext getServletContext(){
        return servletContext;
    }

    private boolean isStaleSession(HttpSession session){
        Object lastAccessedTime = session.getLastAccessedTime();
        if(sessionStorage.isStored(session)){
            lastAccessedTime = sessionStorage.getSessionMetaDataField(session.getId(), SessionMetaData.LAST_ACCESSED_TIME_KEY);
        }
        return  session.getLastAccessedTime() != Long.valueOf(String.valueOf(lastAccessedTime)).longValue();
    }

    private HttpSession getSessionFromLocalOrStorage(String id){
        HttpSession session = findSessionFromLocalAndRefresh(id);
        if(null != session){
            return session;
        }

        logger.info("本地不存在session[{}]的信息，从storage中获取。", id);
        return findSessionFromStorage(id);
    }

    private HttpSession findSessionFromStorage(String id){
        HttpSession session = null;
        SessionMetaData metaData = sessionStorage.getSessionMetaData(id);

        if(null == metaData){
            logger.info("storage中不存在session[{}]的信息。", id);
            return session;
        }

        logger.debug("Session Storage中持有session[{}]的信息.", id);
        logger.info("根据Session Storage中的信息构建session[{}].", id);
        return newHttpSession(metaData);
    }

    private HttpSession findSessionFromLocalAndRefresh(String id){
        HttpSession session = localSessionContainer.get(id);
        if(null != session){
            logger.info("本地持有session[{}]的信息.", id);
            logger.debug("检查是否需要刷新session.");
            if(isStaleSession(session)){
                logger.debug("更新本地session内容与Session Storage中内容一致.");
                ((DistributedHttpSession)session).refresh();
            }else{
                logger.debug("无需刷新.");
            }
        }
        return session;
    }

    private String newSessionId(HttpServletRequest request){
        return sessionIdManager.newSessionId(request, System.currentTimeMillis());
    }

    private void addSessionToLocal(HttpSession session){
        if (null == session) return;
        localSessionContainer.put(session.getId(), session);
    }

    private void removeSessionFromLocal(HttpSession session){
        localSessionContainer.remove(session.getId());
    }

    private void removeSessionFromStorage(HttpSession session){
        sessionStorage.removeSession(session.getId());
    }

    private void startClearInvalidSessionScheduledTask(){
        scheduledExecutorService.scheduleWithFixedDelay(
                new ClearInvalidSessionScheduledTask(localSessionContainer),
                10, 10, TimeUnit.SECONDS);
    }

    private void fireSessionEvent(SessionEventType type, HttpSession session){
        switch (type){
            case SESSION_CREATED_EVENT:
                fireSessionCreatedEvent(session);
                break;
            case SESSION_DESTROYED_EVENT:
                fireSessionDestroyedEvent(session);
                break;
        }
    }

    private void fireSessionCreatedEvent(HttpSession session){
        for (EventListener listener : listeners){
            if(listener instanceof HttpSessionListener){
                ((HttpSessionListener) listener).sessionCreated(new HttpSessionEvent(session));
            }
        }
    }

    private void fireSessionDestroyedEvent(HttpSession session){
        for(EventListener listener : listeners){
            if(listener instanceof HttpSessionListener){
                ((HttpSessionListener) listener).sessionDestroyed(new HttpSessionEvent(session));
            }
        }
    }
}
