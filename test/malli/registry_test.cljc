(ns malli.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.registry :as mr]))

(deftest mutable-test
  (let [registry* (atom {})
        registry (mr/mutable-registry registry*)
        register! (fn [t ?s] (swap! registry* assoc t ?s))]
    (testing "default registy"
      (is (thrown? #?(:clj Exception, :cljs js/Error) (m/validate :str "kikka" {:registry registry})))
      (register! :str (m/-string-schema))
      (is (true? (m/validate :str "kikka" {:registry registry}))))))

(deftest composite-test
  (let [registry* (atom {})
        register! (fn [t ?s] (swap! registry* assoc t ?s))
        registry (mr/composite-registry
                  {:map (m/-map-schema)}
                  (mr/mutable-registry registry*)
                  (mr/dynamic-registry))]

    ;; register
    (register! :maybe (m/-maybe-schema))

    ;; use
    (binding [mr/*registry* {:string (m/-string-schema)}]
      (is (true? (m/validate
                  [:map [:maybe [:maybe :string]]]
                  {:maybe "sheep"}
                  {:registry registry})))
      (is (= #{:string :map :maybe} (-> registry (mr/-schemas) (keys) (set)))))))

(deftest lazy-registry-test
  (let [loads (atom #{})
        registry (mr/lazy-registry
                  (m/default-schemas)
                  (fn [type registry]
                    (let [lookup {"AWS::ApiGateway::UsagePlan" [:map {:closed true}
                                                                [:Type [:= "AWS::ApiGateway::UsagePlan"]]
                                                                [:Description {:optional true} string?]
                                                                [:UsagePlanName {:optional true} string?]]
                                  "AWS::AppSync::ApiKey" [:map {:closed true}
                                                          [:Type [:= "AWS::AppSync::ApiKey"]]
                                                          [:ApiId string?]
                                                          [:Description {:optional true} string?]]}
                          schema (some-> type lookup (m/schema {:registry registry}))]
                      (swap! loads conj type)
                      schema)))
        CloudFormation (m/schema [:multi {:lazy-refs true, :dispatch :Type}
                                  "AWS::ApiGateway::Stage"
                                  "AWS::ApiGateway::UsagePlan"
                                  "AWS::AppSync::ApiKey"]
                                 {:registry registry})]

    (testing "nothing is loaded"
      (is (= 0 (count @loads))))

    (testing "validating a schema pulls schema"
      (is (true? (m/validate
                  CloudFormation
                  {:Type "AWS::AppSync::ApiKey"
                   :ApiId "123"
                   :Description "apkey"})))

      (is (= 1 (count @loads))))

    (testing "pulling more"
      (is (true? (m/validate
                  CloudFormation
                  {:Type "AWS::ApiGateway::UsagePlan"})))

      (is (= 2 (count @loads))))))

(defmulti my-mm identity)

(defmethod my-mm "AWS::AppSync::ApiKey" [_]
    [:map {:closed true}
     [:Type [:= "AWS::AppSync::ApiKey"]]
     [:ApiId string?]
     [:Description {:optional true} string?]])

(defmethod my-mm "AWS::ApiGateway::UsagePlan" [_]
  [:map {:closed true}
   [:Type [:= "AWS::ApiGateway::UsagePlan"]]
   [:Description {:optional true} string?]
   [:UsagePlanName {:optional true} string?]])

(deftest lazy-registry+index-test
  (let [loads (atom #{})
        registry (mr/lazy-registry
                  (m/default-schemas)
                  (fn [type registry]
                    (let [schema (some-> type my-mm (m/schema {:registry registry}))]
                      (swap! loads conj type)
                      schema)))
        CloudFormation (m/schema [:multi {:dispatch :Type
                                          :methods (fn []
                                                     (swap! loads conj :methods)
                                                     (keys (methods my-mm)))
                                          :lazy-refs true,}]
                                 {:registry registry})]

    (testing "nothing is loaded"
      (is (= 0 (count @loads))))

    (testing "validating a schema pulls schema"
      (is (true? (m/validate
                  CloudFormation
                  {:Type "AWS::AppSync::ApiKey"
                   :ApiId "123"
                   :Description "apkey"})))

      (is (= 2 (count @loads))))

    (testing "pulling more"
      (is (true? (m/validate
                  CloudFormation
                  {:Type "AWS::ApiGateway::UsagePlan"})))

      (is (= 3 (count @loads))))

    (testing "pulling again"
      (is (true? (m/validate
                  CloudFormation
                  {:Type "AWS::ApiGateway::UsagePlan"})))

      (is (= 3 (count @loads))))))
