(ns de.otto.tesla.xray.xraychecker-test
  (:require
    [clojure.test :refer :all]
    [de.otto.tesla.xray.xray-checker :as chkr]
    [de.otto.tesla.xray.check :as chk]
    [com.stuartsierra.component :as comp]
    [de.otto.tesla.system :as tesla]
    [de.otto.tesla.util.test-utils :refer [eventually]]
    [com.stuartsierra.component :as c]
    [de.otto.tesla.xray.util.utils :as utils]
    [de.otto.tesla.stateful.handler :as handler]
    [ring.mock.request :as mock])
  (:import (org.joda.time DateTimeZone DateTime)))

(defrecord DummyCheck []
  chk/XRayCheck
  (start-check [_ _]
    (chk/->XRayCheckResult :ok "dummy-message")))

(defrecord ErrorCheck []
  chk/XRayCheck
  (start-check [_ _]
    (chk/->XRayCheckResult :error "error-message")))

(defrecord BlockingCheck []
  chk/XRayCheck
  (start-check [_ _]
    (while true
      (Thread/sleep 1000))))

(defrecord DummyCheckWithoutReturnedStatus []
  chk/XRayCheck
  (start-check [_ _]))

(defrecord AssertionCheck []
  chk/XRayCheck
  (start-check [_ _]
    (assert (= 1 2))))

(defrecord WaitingDummyCheck [t]
  chk/XRayCheck
  (start-check [_ _]
    (Thread/sleep t)
    (chk/->XRayCheckResult :ok t)))

(defrecord FailingCheck []
  chk/XRayCheck
  (start-check [_ _]
    (throw (RuntimeException. "failing message"))))

(def start-the-xraychecks #'chkr/start-the-xraychecks)

(defn test-system [runtime-config]
  (-> (tesla/base-system (assoc runtime-config :name "test-system"))
      (assoc :xray-checker (c/using (chkr/new-xraychecker "test") [:handler :config :scheduler]))))

(deftest check-scheduling
  (testing "should execute checks with configured check-frequency"
    (with-redefs [utils/current-time (fn [] 10)]
      (let [started (comp/start (test-system {:test-check-frequency    "100"
                                              :test-check-environments "dev"
                                              :test-max-check-history  "2"}))
            xray-checker (:xray-checker started)]
        (chkr/register-check xray-checker (->DummyCheck) "DummyCheckA" "Checking A")
        (chkr/register-check xray-checker (->DummyCheck) "DummyCheckB" "Checking B")
        (try
          (is (= {"DummyCheckA" (chkr/->RegisteredXRayCheck (->DummyCheck) "DummyCheckA" "Checking A" chkr/default-strategy)
                  "DummyCheckB" (chkr/->RegisteredXRayCheck (->DummyCheck) "DummyCheckB" "Checking B" chkr/default-strategy)}
                 @(:registered-checks xray-checker)))
          (eventually (= {"DummyCheckA" {"dev" {:overall-status :ok
                                                :results        [(chk/->XRayCheckResult :ok "dummy-message" 0 10)]}}
                          "DummyCheckB" {"dev" {:overall-status :ok
                                                :results        [(chk/->XRayCheckResult :ok "dummy-message" 0 10)]}}}
                         @(:check-results xray-checker)))
          (finally
            (comp/stop started)))
        ))))


(deftest should-handle-blocking-checks
  (testing "should timeout blocking-checks before they run again"
    (with-redefs [utils/current-time (fn [] 10)]
      (let [started (comp/start (test-system {:test-check-frequency    "100"
                                              :test-check-environments "dev"
                                              :test-max-check-history  "2"}))
            xray-checker (:xray-checker started)]
        (chkr/register-check xray-checker (->BlockingCheck) "BlockingCheck" "Checking Blocking")
        (try
          (is (= {"BlockingCheck" (chkr/->RegisteredXRayCheck (->BlockingCheck) "BlockingCheck" "Checking Blocking" chkr/default-strategy)}
                 @(:registered-checks xray-checker)))
          (eventually (= {"BlockingCheck" {"dev" {:overall-status :error
                                                  :results        [(chk/->XRayCheckResult :error "BlockingCheck did not finish in 100 ms" 100 10)
                                                                   (chk/->XRayCheckResult :error "BlockingCheck did not finish in 100 ms" 100 10)]}}}
                         @(:check-results xray-checker)))
          (finally
            (comp/stop started)))))))

(deftest checks-and-check-results
  (testing "should register, check and store results"
    (with-redefs [utils/current-time (fn [] 10)]
      (let [started (comp/start (test-system {:test-check-frequency    "100"
                                              :test-check-environments "dev"
                                              :test-max-check-history  "2"}))
            xray-checker (:xray-checker started)]
        (chkr/register-check xray-checker (->DummyCheck) "DummyCheckA" "Checking A")
        (chkr/register-check xray-checker (->DummyCheck) "DummyCheckB" "Checking B")
        (try
          (is (= {"DummyCheckA" (chkr/->RegisteredXRayCheck (->DummyCheck) "DummyCheckA" "Checking A" chkr/default-strategy)
                  "DummyCheckB" (chkr/->RegisteredXRayCheck (->DummyCheck) "DummyCheckB" "Checking B" chkr/default-strategy)}
                 @(:registered-checks xray-checker)))
          (start-the-xraychecks xray-checker)
          (is (= {"DummyCheckA" {"dev" {:overall-status :ok
                                        :results        [(chk/->XRayCheckResult :ok "dummy-message" 0 10)]}}
                  "DummyCheckB" {"dev" {:overall-status :ok
                                        :results        [(chk/->XRayCheckResult :ok "dummy-message" 0 10)]}}}
                 @(:check-results xray-checker)))
          (start-the-xraychecks xray-checker)
          (is (= {"DummyCheckA" {"dev" {:overall-status :ok
                                        :results        [(chk/->XRayCheckResult :ok "dummy-message" 0 10)
                                                         (chk/->XRayCheckResult :ok "dummy-message" 0 10)]}}
                  "DummyCheckB" {"dev" {:overall-status :ok
                                        :results        [(chk/->XRayCheckResult :ok "dummy-message" 0 10)
                                                         (chk/->XRayCheckResult :ok "dummy-message" 0 10)]}}}
                 @(:check-results xray-checker)))
          (start-the-xraychecks xray-checker)
          (is (= {"DummyCheckA" {"dev" {:overall-status :ok
                                        :results        [(chk/->XRayCheckResult :ok "dummy-message" 0 10)
                                                         (chk/->XRayCheckResult :ok "dummy-message" 0 10)]}}
                  "DummyCheckB" {"dev" {:overall-status :ok
                                        :results        [(chk/->XRayCheckResult :ok "dummy-message" 0 10)
                                                         (chk/->XRayCheckResult :ok "dummy-message" 0 10)]}}}
                 @(:check-results xray-checker)))
          (finally
            (comp/stop started)))))))

(deftest acknowledging-test
  (testing "should acknowledge single check using POST endpoint"
    (let [started (comp/start (test-system {:test-check-frequency    "200000"
                                            :test-check-environments "dev"
                                            :test-max-check-history  "2"}))
          {:keys [xray-checker handler]} started
          handler-fn (handler/handler handler)
          current-time 10
          acknowledge-hours 15
          acknowledge-hours-in-millis (* 15 60 60 1000)]
      (chkr/register-check xray-checker (->ErrorCheck) "ErrorCheck")
      (try
        (with-redefs [utils/current-time (constantly current-time)]
          ;nothing on startup
          (is (= {} @(:acknowledged-checks xray-checker)))
          (is (= {} @(:check-results xray-checker)))

          ;plain error result after first check
          (start-the-xraychecks xray-checker)
          (is (= {} @(:acknowledged-checks xray-checker)))
          (is (= {"ErrorCheck" {"dev" {:overall-status :error
                                       :results        [(chk/->XRayCheckResult :error "error-message" 0 10)]}}}
                 @(:check-results xray-checker)))

          ;ACKNOWLEDGE!
          (is (= 204 (-> (handler-fn (mock/request :post (str "/xray-checker/acknowledged-checks?check-id=ErrorCheck&environment=dev&hours=" acknowledge-hours)))
                         :status)))
          ;acknowledged results
          (start-the-xraychecks xray-checker)
          (is (= {"ErrorCheck" {"dev" (+ acknowledge-hours-in-millis current-time)}}
                 @(:acknowledged-checks xray-checker)))
          (is (= {"ErrorCheck" {"dev" {:overall-status :acknowledged
                                       :results        [(chk/->XRayCheckResult :acknowledged "error-message; Acknowledged" 0 10)
                                                        (chk/->XRayCheckResult :error "error-message" 0 10)]}}}
                 @(:check-results xray-checker))))
        (finally
          (comp/stop started)))))

  (testing "should cleanup acknowledgement after configured time"
    (let [started (comp/start (test-system {:test-check-frequency    "200000"
                                            :test-check-environments "dev"
                                            :test-max-check-history  "2"}))
          {:keys [xray-checker handler]} started
          handler-fn (handler/handler handler)
          start-time 10
          acknowledge-hours 15
          acknowledge-hours-in-millis (* 15 60 60 1000)
          time-after-acknowledged-check (+ start-time acknowledge-hours-in-millis 1)]
      (chkr/register-check xray-checker (->ErrorCheck) "ErrorCheck")
      (try
        (with-redefs [utils/current-time (constantly start-time)]
          ;ACKNOWLEDGE!
          (is (= 204 (-> (handler-fn (mock/request :post (str "/xray-checker/acknowledged-checks?check-id=ErrorCheck&environment=dev&hours=" acknowledge-hours)))
                         :status)))
          ;acknowledged results
          (start-the-xraychecks xray-checker)
          (is (= {"ErrorCheck" {"dev" (+ acknowledge-hours-in-millis start-time)}}
                 @(:acknowledged-checks xray-checker)))
          (is (= {"ErrorCheck" {"dev" {:overall-status :acknowledged
                                       :results        [(chk/->XRayCheckResult :acknowledged "error-message; Acknowledged" 0 start-time)]}}}
                 @(:check-results xray-checker))))

        (with-redefs [utils/current-time (constantly time-after-acknowledged-check)]
          (start-the-xraychecks xray-checker)
          (is (= {} @(:acknowledged-checks xray-checker)))
          (is (= {"ErrorCheck" {"dev" {:overall-status :error
                                       :results        [(chk/->XRayCheckResult :error "error-message" 0 time-after-acknowledged-check)
                                                        (chk/->XRayCheckResult :acknowledged "error-message; Acknowledged" 0 start-time)]}}}
                 @(:check-results xray-checker))))
        (finally
          (comp/stop started)))))

  (testing "should delete acknowledgement"
    (let [started (comp/start (test-system {:test-check-frequency    "200000"
                                            :test-check-environments "dev"
                                            :test-max-check-history  "2"}))
          {:keys [xray-checker handler]} started
          handler-fn (handler/handler handler)
          start-time 10
          acknowledge-hours 15
          acknowledge-hours-in-millis (* 15 60 60 1000)
          time-after-acknowledged-check (+ start-time acknowledge-hours-in-millis 1)]
      (chkr/register-check xray-checker (->ErrorCheck) "ErrorCheck")
      (try
        (with-redefs [utils/current-time (constantly start-time)]
          ;ACKNOWLEDGE!
          (is (= 204 (-> (handler-fn (mock/request :post (str "/xray-checker/acknowledged-checks?check-id=ErrorCheck&environment=dev&hours=" acknowledge-hours)))
                         :status)))
          (is (= {"ErrorCheck" {"dev" (+ acknowledge-hours-in-millis start-time)}} @(:acknowledged-checks xray-checker)))
          ;DELETE ACKNOWLEDGEMENT!
          (is (= 204 (-> (handler-fn (mock/request :delete "/xray-checker/acknowledged-checks/ErrorCheck/dev"))
                         :status)))
          (is (= {} @(:acknowledged-checks xray-checker))))
        (finally
          (comp/stop started))))))

(deftest error-handling
  (testing "should store warning-result for check without a propper return message"
    (with-redefs [utils/current-time (fn [] 10)]
      (let [started (comp/start (test-system {:test-check-frequency    "100"
                                              :test-check-environments "dev"}))
            xray-checker (:xray-checker started)]
        (try
          (chkr/register-check xray-checker (->DummyCheckWithoutReturnedStatus) "DummyCheckWithoutReturnedStatus")
          (eventually (= {"DummyCheckWithoutReturnedStatus" {"dev" {:overall-status :warning
                                                                    :results        [(chk/->XRayCheckResult :warning "no xray-result returned by check" 0 10)]}}}
                         @(:check-results xray-checker)))
          (finally
            (comp/stop started))))))
  (testing "should be able to catch assertions in test"
    (with-redefs [utils/current-time (fn [] 10)]
      (let [started (comp/start (test-system {:test-check-frequency    "100"
                                              :test-check-environments "dev"}))
            xray-checker (:xray-checker started)]
        (try
          (chkr/register-check xray-checker (->AssertionCheck) "AssertionCheck")
          (eventually (= {"AssertionCheck" {"dev" {:overall-status :error
                                                   :results        [(chk/->XRayCheckResult :error "Assert failed: (= 1 2)" 0 10)]}}}
                         @(:check-results xray-checker)))
          (finally
            (comp/stop started)))))))

(deftest request-handling-and-html-responses
  (with-redefs [utils/current-time (fn [] 1447152024778)]
    (let [started (comp/start (test-system {:test-check-frequency    "100"
                                            :test-check-environments "dev"}))
          xray-checker (:xray-checker started)
          handlers (handler/handler (:handler started))]
      (try
        (chkr/register-check xray-checker (->DummyCheck) "DummyCheck")
        (start-the-xraychecks xray-checker)

        (testing "should visualize the response on overview-page"
          (let [response (handlers (mock/request :get "/xray-checker/overview"))]
            (is (= 200 (:status response)))
            (is (= {"Content-Type" "text/html"} (:headers response)))
            (is (= true (.contains (:body response) "2015.11.10 11:40:24 tt:0 dummy-message")))))

        (testing "should visualize the overall-status on root-page"
          (let [response (handlers (mock/request :get "/xray-checker"))]
            (is (= 200 (:status response)))
            (is (= {"Content-Type" "text/html"} (:headers response)))
            (is (= true (.contains (:body response) "ok")))))

        (testing "should visualize the detail-status on detail-page"
          (let [response (handlers (mock/request :get "/xray-checker/detail/DummyCheck/dev"))]
            (is (= 200 (:status response)))
            (is (= {"Content-Type" "text/html"} (:headers response)))
            (is (= true (.contains (:body response) "dev")))
            (is (= true (.contains (:body response) "2015.11.10 11:40:24 tt:0 dummy-message")))))
        (testing "should emit cc xml for monitors"
          (let [response (handlers (mock/request :get "/cc.xml"))]
            (is (= 200 (:status response)))
            (is (= {"Content-Type" "text/xml"} (:headers response)))
            (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Projects><Project name=\"DummyCheck on dev\" last-build-time=\"2015-11-10T10:40:24.778Z\" lastBuildStatus=\":ok\"></Project></Projects>"
                   (:body response)))))

        (finally
          (comp/stop started))))))

(deftest execution-in-parallel
  (testing "should execute checks in parallel"
    (with-redefs [utils/current-time (fn [] 10)]
      (let [started (comp/start (test-system {:test-check-frequency    "9999999"
                                              :test-check-environments "dev"}))
            xray-checker (:xray-checker started)]
        (try
          (chkr/register-check xray-checker (->WaitingDummyCheck 0) "DummyCheck1")
          (chkr/register-check xray-checker (->WaitingDummyCheck 100) "DummyCheck2")
          (chkr/register-check xray-checker (->WaitingDummyCheck 100) "DummyCheck3")
          (start-the-xraychecks xray-checker)               ; wait for start
          (eventually (= {"DummyCheck1" {"dev" {:overall-status :ok
                                                :results        [(chk/->XRayCheckResult :ok 0 0 10)]}}
                          "DummyCheck2" {"dev" {:overall-status :ok
                                                :results        [(chk/->XRayCheckResult :ok 100 0 10)]}}
                          "DummyCheck3" {"dev" {:overall-status :ok
                                                :results        [(chk/->XRayCheckResult :ok 100 0 10)]}}}
                         @(:check-results xray-checker)))
          (finally
            (comp/stop started)))))))

(deftest stringify-acknowledged-checks-test
  (testing "should format timestamp properly"
    (with-redefs [chkr/as-date-time (fn [millis] (DateTime. millis DateTimeZone/UTC))]
      (is (= "{\"testCheck\":{\"dev\":\"1 November, 08:27\"}}"
             (chkr/stringify-acknowledged-checks (atom {"testCheck" {"dev" 1477988830064}})))))))

(deftest acknowledge-check-endpoints
  (testing "should put a check object with correct expire time in the acknowledged-checks atom"
    (with-redefs [utils/current-time (constantly 10)]
      (let [acknowledged-checks (atom {})]
        (chkr/acknowledge-check! (atom {}) acknowledged-checks "oneHourAcknowledgement" "test-env" "1")
        (is (= ["oneHourAcknowledgement" {"test-env" (+ 10 (* 60 60 1000))}] (first @acknowledged-checks))))))

  (testing "should keep other environments unchanged when adding ack for same check in different env"
    (with-redefs [utils/current-time (constantly 10)]
      (let [acknowledged-checks (atom {"oneHourAcknowledgement" {"otherEnv" 20}})]
        (chkr/acknowledge-check! (atom {}) acknowledged-checks "oneHourAcknowledgement" "test-env" "1")
        (is (= ["oneHourAcknowledgement" {"test-env" (+ 10 (* 60 60 1000))
                                          "otherEnv" 20}] (first @acknowledged-checks))))))

  (testing "should immediatly change the overall status to acknowledged"
    (let [acknowledged-checks (atom {})
          check-results (atom {"testCheck" {"test-env" {:overall-status :error}}})]
      (chkr/acknowledge-check! check-results acknowledged-checks "testCheck" "test-env" "1")
      (is (= {"testCheck" {"test-env" {:overall-status :acknowledged}}}
             @check-results))))

  (testing "should remove a check object regardless of it's expire time"
    (let [acknowledged-checks (atom {"checkToBeRemoved" {"testEnv" 1000}})]
      (chkr/remove-acknowledgement! acknowledged-checks "checkToBeRemoved" "testEnv")
      (is (= {} @acknowledged-checks))))
  (testing "should only remove one env and keep the other one"
    (let [acknowledged-checks (atom {"checkToBeRemoved" {"dropEnv" 1000
                                                         "keepEnv" 100}})]
      (chkr/remove-acknowledgement! acknowledged-checks "checkToBeRemoved" "dropEnv")
      (is (= {"checkToBeRemoved" {"keepEnv" 100}} @acknowledged-checks)))))

(def build-check-id-env-vecs #'chkr/build-check-id-env-vecs)
(deftest building-parameters-for-futures
  (testing "should build a propper parameter vector for all checks"
    (let [check-a (chkr/->RegisteredXRayCheck "A" "A" "A" "A")
          check-b (chkr/->RegisteredXRayCheck "B" "B" "B" "B")]
      (is (= [[check-a "dev"] [check-a "test"]
              [check-b "dev"] [check-b "test"]]
             (build-check-id-env-vecs ["dev" "test"] {"CheckA" check-a
                                                        "CheckB" check-b}))))))


(deftest clear-outdated-acknowledgements!
  (testing "should clear outdated acknowledgements"
    (with-redefs [utils/current-time (constantly 20)]
      (let [state (atom {"CheckA" {"dev"  20
                                   "prod" 30}
                         "CheckB" {"dev"  30
                                   "prod" 10}
                         "CheckC" {"prod" 10}})]
        (chkr/clear-outdated-acknowledgements! {:acknowledged-checks state})
        (is (= {"CheckA" {"prod" 30}
                "CheckB" {"dev" 30}}
               @state)))))

  (testing "should acknowledge check and clear acknowledgements"
    (let [mock-time 20]
      (with-redefs [utils/current-time (constantly mock-time)]
        (let [check-results (atom {})
              hour-as-millis (* 60 60 1000)
              acknowledged-checks (atom {})]
          (chkr/acknowledge-check! check-results acknowledged-checks "CheckA" "dev" "1")
          (is (= {"CheckA" {"dev" (+ (* 1 hour-as-millis) mock-time)}} @acknowledged-checks))
          (chkr/acknowledge-check! check-results acknowledged-checks "CheckB" "prod" "1")
          (is (= {"CheckA" {"dev" (+ (* 1 hour-as-millis) mock-time)}
                  "CheckB" {"prod" (+ (* 1 hour-as-millis) mock-time)}} @acknowledged-checks))
          (with-redefs [utils/current-time (constantly (+ (* 2 hour-as-millis) mock-time))]
            (chkr/clear-outdated-acknowledgements! {:acknowledged-checks acknowledged-checks})
            (is (= {} @acknowledged-checks))
            (chkr/clear-outdated-acknowledgements! {:acknowledged-checks acknowledged-checks})
            (is (= {} @acknowledged-checks))))))))
