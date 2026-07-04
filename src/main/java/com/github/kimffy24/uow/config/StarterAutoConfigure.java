package com.github.kimffy24.uow.config;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.kimffy24.uow.core.ExecutingContextFactory;
import com.github.kimffy24.uow.service.CommittingService;
import com.github.kimffy24.uow.service.RepositoryProvider;
import com.github.kimffy24.uow.service.SpringUoWMapperProvider;

@Configuration
@ConditionalOnProperty(prefix = "com.github.kimffy24.uow", value = "enabled", havingValue = "true")
@EnableConfigurationProperties(StarterServiceProperties.class)
public class StarterAutoConfigure {

    @Autowired
    private StarterServiceProperties properties;
    
    @AutoConfigureOrder(1)
    @Bean
    @ConditionalOnMissingBean
    CommittingService provideCommittingService(BeanFactory beanFactory){
    	AutowireCapableBeanFactory listableBeanFactory = (AutowireCapableBeanFactory)beanFactory;
		return listableBeanFactory.createBean(CommittingService.class);
    }

    @AutoConfigureOrder(3)
    @Bean
    @ConditionalOnMissingBean
    SpringUoWMapperProvider provideSpringUoWMapperBinder(BeanFactory beanFactory){
    	AutowireCapableBeanFactory listableBeanFactory = (AutowireCapableBeanFactory)beanFactory;
		return listableBeanFactory.createBean(SpringUoWMapperProvider.class);
    }

    @AutoConfigureOrder(4)
    @Bean
    @ConditionalOnMissingBean
    RepositoryProvider provideRepositoryHub(BeanFactory beanFactory){
    	AutowireCapableBeanFactory listableBeanFactory = (AutowireCapableBeanFactory)beanFactory;
		return listableBeanFactory.createBean(RepositoryProvider.class);
    }

    @AutoConfigureOrder(5)
    @Bean(initMethod = "init")
    @ConditionalOnMissingBean
    ExecutingContextFactory provideExecutingContextFactory(RepositoryProvider reposProvider){
		return new ExecutingContextFactory(reposProvider);
    }
}
