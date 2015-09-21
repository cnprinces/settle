package account

import groovyx.net.http.HTTPBuilder
import org.codehaus.groovy.grails.commons.ConfigurationHolder
import static groovyx.net.http.ContentType.JSON
import static groovyx.net.http.Method.POST

class SettleClientService {

  static transactional = true

  def accountClientService
  def liquidateService

  /**
   * ����㽻������ͬ������
   * @param srvCode ҵ�����
   * @param tradeCode �������ͱ���
   * @param customerNo �ͻ���
   * @param amount ���׽�����
   * @param seqNo ������ˮ��
   * @param tradeDate ����ʱ�䣬��ʽΪ��yyyy-MM-dd HH:mm:ss.SSS
   * @param billDate ����ʱ�䣬��ʽΪ��yyyy-MM-dd HH:mm:ss.SSS
   * @return { result : 'true or false', errorMsg: ''}
   * result: trueΪ�ɹ��� false Ϊʧ��,
   * errorMsg: ��resultΪfalseʱ��������ԭ��
   * @throws Exception
   */
  def trade(srvCode, tradeCode, customerNo, amount, seqNo, tradeDate, billDate, channel) throws Exception {
    def http = new HTTPBuilder(ConfigurationHolder.config.settle.serverUrl)
    http.request(POST, JSON) { req ->
      uri.path = 'rpc/trade'
      body = [srvCode: srvCode, tradeCode: tradeCode, customerNo: customerNo, amount: amount, seqNo: seqNo, tradeDate: tradeDate, billDate: billDate, channel: channel]
      response.success = { resp, json ->
        return json
      }
      response.failure = { resp ->
        throw new Exception('request error')
      }
    }
  }

    // test
    def test1(amount, channel) {
        println "################# channel  " + channel
        Random random = new Random()
        def seqNo = 101202170030000 + Math.abs(random.nextInt()%10000)
        def settleDate = new Date()
        def customerNo = '100000000001524'

        def srvType = settle.FtSrvType.findBySrvCode('online')
        def tradeType = settle.FtSrvTradeType.findBySrvAndTradeCode(srvType, 'payment')
//        def feeSetting = settle.FtTradeFee.findWhere([srv: srvType, tradeType:tradeType, customerNo: '100000000001524', channelCode:channel])
        def feeSetting = liquidateService.getFeeSetting(srvType, tradeType, customerNo, channel,settleDate)

        if (!feeSetting) {
//            feeSetting = settle.FtTradeFee.findWhere([srv: srvType, tradeType:tradeType, customerNo: '100000000001524', channelCode:null])
            feeSetting = liquidateService.getFeeSetting(srvType, tradeType, customerNo,null,settleDate)
        }

        //����������־��¼
        def trade = new settle.FtTrade()
        trade.srvCode = 'online'
        trade.tradeCode = 'payment'
        trade.customerNo = '100000000001524'
        trade.amount = amount
        trade.seqNo = seqNo
        trade.channelCode = channel
        trade.realtimeSettle = 1
        trade.liqDate = new Date()
        trade.tradeDate = new Date()
        trade.billDate = new Date()

      // �Ʒ�ģʽΪ������� �޷�ʵʱ����
      if (feeSetting.feeModel == 1) {
        log.warn('feeModel is 1, save only')
        try {
          trade.realtimeSettle = 0
          trade.liqDate = null
          trade.save(faleOnError: true)
        } catch (Exception e) {
          log.warn('save trade false', e)
        }
        return
      }
      // �������ڳ���������Ч�� �޷�ʵʱ����
      if (feeSetting.feeModel != 1) {
        def settleDay = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date())
        def dateBegin = new java.text.SimpleDateFormat("yyyy-MM-dd").format(feeSetting.dateBegin)
        def dateEnd = new java.text.SimpleDateFormat("yyyy-MM-dd").format(feeSetting.dateEnd)
        if((dateBegin > settleDay) || (dateEnd < settleDay)) {
            log.warn('(dateBegin > settleDay) || (dateEnd < settleDay), save only')
            try {
              trade.realtimeSettle = 0
              trade.liqDate = null
              trade.save(faleOnError: true)
            } catch (Exception e) {
              log.warn('save trade false', e)
            }
            return
        }
      }
        def feeAmount = liquidateService.calcuFeeUpgrade(feeSetting, null, 'online', 'payment', amount, 1, settleDate)
        println "=========================================="
        println feeAmount
        println "=========================================="

      feeAmount = new BigDecimal(feeAmount.toString()).setScale(0, BigDecimal.ROUND_HALF_UP).longValue()

    //��ѯϵͳ�������˻�
    def sysFeeAcc = boss.BoInnerAccount.findByKey('feeAcc').accountNo

      //���㽻�׾���
      def netAmount = amount * tradeType.netWeight
      trade.netAmount = netAmount
      if (feeAmount == null) {
        feeAmount = new BigDecimal(0)
      }
      log.info "netAmount is ${netAmount}, fee is ${feeAmount}"
      //������ת��
      def cmdList = null
      try {
        def customer = ismp.CmCustomer.findByCustomerNo(customerNo)
        def service = boss.BoCustomerService.findByCustomerIdAndServiceCode(customer.id, 'online')
        //�жϼ��ջ��Ǻ�
        if (feeSetting.fetchType == 0) { //����
          //���ý���������
          trade.preFee = feeAmount
          trade.feeType = 0

          //�ѽ��׾����ȥ�����ѵĽ��ӿͻ������˻�ת���ͻ��ֽ��ʻ������Ϊ������
          def settleAmount = netAmount - feeAmount
          cmdList = accountClientService.buildTransfer(cmdList, service.srvAccNo, customer.accountNo, settleAmount, 'settle', seqNo, '0', "ʵʱ���㽻�׾���")

          //�������Ѵӿͻ������ʻ��۳���ϵͳ�������ʻ������Ϊ������ת��
          cmdList = accountClientService.buildTransfer(cmdList, service.srvAccNo, sysFeeAcc, feeAmount, 'fee', seqNo, '0', "ʵʱ���㼴��������")

        } else { //��
          //���ý���������
          trade.postFee = feeAmount
          trade.feeType = 1
          //�ѽ��׾���ӿͻ������˻�ת���ͻ��ֽ��ʻ������Ϊ������
          cmdList = accountClientService.buildTransfer(cmdList, service.srvAccNo, customer.accountNo, netAmount, 'settle', seqNo, '0', "ʵʱ���㽻�׾���")
          //�������Ѵӷ�����������ʻ�ת��ϵͳӦ���������ʻ������������Ϊ������
          //��ѯϵͳӦ���������˻�
          def sysFeeAdvAcc = boss.BoInnerAccount.findByKey('feeInAdvance').accountNo
          if (!service) {
            log.warn("service not found")
            return
          }
          cmdList = accountClientService.buildTransfer(cmdList, service.feeAccNo, sysFeeAdvAcc, feeAmount, 'fee',seqNo, '0', "ʵʱ�������������")
        }
      } catch (Exception e) {
        log.warn("gen cmdList false", e)
        return
      }
      trade.redo = false
      trade.save(failOnError:true)

      //ת��
      boolean redo = false //ת��ʧ�ܣ��Ƿ�����
      try {
        def transResult = accountClientService.batchCommand(UUID.randomUUID().toString().replaceAll('-', ''), cmdList)
        if (transResult.result != 'true') {
          log.warn("ʵʱת��ʧ�ܣ������룺${transResult.errorCode},������Ϣ��${transResult.errorMsg},cmdList:${cmdList}")
          //�ʻ������������ϵͳ������Ҫ����ת��
          if (transResult.errorCode == '03' || transResult.errorCode == 'ff') {
            redo = true
          }
        }
      } catch (Exception e) {
        log.warn("balance trans faile,cmdList:${cmdList}", e)
        redo = true
      }

      if (redo) {
        trade.redo = true
        trade.save(failOnError:true)
      }

    }


    // ��������
    def testTrade(amount, channel) {
        println "################# channel  " + channel
        Random random = new Random()
        def seqNo = 101202170030000 + Math.abs(random.nextInt()%10000)
        def settleDate = new Date()
        def customerNo = '100000000001524'

        def srvType = settle.FtSrvType.findBySrvCode('online')
        def tradeType = settle.FtSrvTradeType.findBySrvAndTradeCode(srvType, 'payment')
//        def feeSetting = settle.FtTradeFee.findWhere([srv: srvType, tradeType:tradeType, customerNo: '100000000001524', channelCode:channel])
        def feeSetting = liquidateService.getFeeSetting(srvType, tradeType, customerNo, channel,settleDate)

        if (!feeSetting) {
//            feeSetting = settle.FtTradeFee.findWhere([srv: srvType, tradeType:tradeType, customerNo: '100000000001524', channelCode:null])
            feeSetting = liquidateService.getFeeSetting(srvType, tradeType, customerNo,null,settleDate)
        }

        //����������־��¼
        def trade = new settle.FtTrade()
        trade.srvCode = 'online'
        trade.tradeCode = 'payment'
        trade.customerNo = '100000000001524'
        trade.amount = amount
        trade.seqNo = seqNo
        trade.channelCode = channel
        trade.realtimeSettle = 0
        trade.liqDate = new Date()
        trade.tradeDate = new Date()
        trade.billDate = new Date()
        trade.liqDate = null
        trade.save(faleOnError: true)

//      // �Ʒ�ģʽΪ������� �޷�ʵʱ����
//      if (feeSetting.feeModel == 1) {
//        log.warn('feeModel is 1, save only')
//        try {
//          trade.realtimeSettle = 0
//          trade.liqDate = null
//          trade.save(faleOnError: true)
//        } catch (Exception e) {
//          log.warn('save trade false', e)
//        }
//        return
//      }
//      // �������ڳ���������Ч�� �޷�ʵʱ����
//      if (feeSetting.feeModel != 1) {
//        def settleDay = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date())
//        def dateBegin = new java.text.SimpleDateFormat("yyyy-MM-dd").format(feeSetting.dateBegin)
//        def dateEnd = new java.text.SimpleDateFormat("yyyy-MM-dd").format(feeSetting.dateEnd)
//        if((dateBegin > settleDay) || (dateEnd < settleDay)) {
//            log.warn('(dateBegin > settleDay) || (dateEnd < settleDay), save only')
//            try {
//              trade.realtimeSettle = 0
//              trade.liqDate = null
//              trade.save(faleOnError: true)
//            } catch (Exception e) {
//              log.warn('save trade false', e)
//            }
//            return
//        }
//      }
    }
}
