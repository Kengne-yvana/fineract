/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.cob.loan;

import org.apache.fineract.cob.COBBusinessStepService;
import org.apache.fineract.cob.common.CustomJobParameterResolver;
import org.apache.fineract.cob.data.LoanCOBParameter;
import org.apache.fineract.cob.listener.COBExecutionListenerRunner;
import org.apache.fineract.infrastructure.event.business.service.BusinessEventNotifierService;
import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.apache.fineract.infrastructure.springbatch.PropertyService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.integration.config.annotation.EnableBatchIntegration;
import org.springframework.batch.integration.partition.RemotePartitioningManagerStepBuilderFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;

@Configuration
@EnableBatchIntegration
@ConditionalOnProperty(value = "fineract.mode.batch-manager-enabled", havingValue = "true")
public class LoanCOBManagerConfiguration {

    @Autowired
    private JobBuilderFactory jobBuilderFactory;
    @Autowired
    private RemotePartitioningManagerStepBuilderFactory stepBuilderFactory;
    @Autowired
    private StepBuilderFactory localStepBuilderFactory;
    @Autowired
    private PropertyService propertyService;
    @Autowired
    private DirectChannel outboundRequests;
    @Autowired
    private COBBusinessStepService cobBusinessStepService;
    @Autowired
    private JobOperator jobOperator;
    @Autowired
    private JobExplorer jobExplorer;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private RetrieveLoanIdService retrieveLoanIdService;
    @Autowired
    private BusinessEventNotifierService businessEventNotifierService;
    @Autowired
    private CustomJobParameterResolver customJobParameterResolver;
    @Autowired
    private LoanLockingService loanLockingService;

    @Bean
    @JobScope
    public LoanCOBPartitioner partitioner(@Value("#{jobExecutionContext['loanCobParameter']}") LoanCOBParameter loanCOBParameter) {
        return new LoanCOBPartitioner(propertyService, cobBusinessStepService, jobOperator, jobExplorer, loanCOBParameter);
    }

    @Bean
    public Step loanCOBStep() {
        return stepBuilderFactory.get(LoanCOBConstant.LOAN_COB_PARTITIONER_STEP)
                .partitioner(LoanCOBConstant.LOAN_COB_WORKER_STEP, partitioner(null)).outputChannel(outboundRequests).build();
    }

    @Bean
    public Step loanIdParameterStep() {
        return localStepBuilderFactory.get("Set loan ID parameter - Step").tasklet(loanIdParameterTasklet()).build();
    }

    @Bean
    public Step lockStep() {
        return localStepBuilderFactory.get("Lock loan accounts - Step").tasklet(lockLoanTasklet()).build();
    }

    @Bean
    public Step resolveCustomJobParametersStep() {
        return localStepBuilderFactory.get("Resolve custom job parameters - Step").tasklet(resolveCustomJobParametersTasklet())
                .listener(customJobParametersPromotionListener()).build();
    }

    @Bean
    public Step stayedLockedStep() {
        return localStepBuilderFactory.get("Stayed locked loan accounts - Step").tasklet(stayedLockedTasklet()).build();
    }

    @Bean
    @JobScope
    public LoanIdParameterTasklet loanIdParameterTasklet() {
        return new LoanIdParameterTasklet(retrieveLoanIdService, customJobParameterResolver);
    }

    @Bean
    @JobScope
    public LockLoanTasklet lockLoanTasklet() {
        return new LockLoanTasklet(loanLockingService, customJobParameterResolver);
    }

    @Bean
    @JobScope
    public ResolveLoanCOBCustomJobParametersTasklet resolveCustomJobParametersTasklet() {
        return new ResolveLoanCOBCustomJobParametersTasklet(customJobParameterResolver);
    }

    @Bean
    @JobScope
    public StayedLockedLoansTasklet stayedLockedTasklet() {
        return new StayedLockedLoansTasklet(businessEventNotifierService, retrieveLoanIdService);
    }

    @Bean(name = "loanCOBJob")
    public Job loanCOBJob() {
        return jobBuilderFactory.get(JobName.LOAN_COB.name()) //
                .listener(new COBExecutionListenerRunner(applicationContext, JobName.LOAN_COB.name())) //
                .start(resolveCustomJobParametersStep()) //
                .next(loanIdParameterStep()).next(lockStep()).next(loanCOBStep()).next(stayedLockedStep()) //
                .incrementer(new RunIdIncrementer()) //
                .build();
    }

    @Bean
    public ExecutionContextPromotionListener customJobParametersPromotionListener() {
        ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[] { LoanCOBConstant.BUSINESS_DATE_PARAMETER_NAME });
        return listener;
    }
}
