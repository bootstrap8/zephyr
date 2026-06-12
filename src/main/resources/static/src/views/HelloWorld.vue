<script lang="ts" setup>
import {
  Edit, ArrowLeft
} from '@element-plus/icons-vue'
import {ref, reactive, onMounted, computed, provide, inject} from 'vue'
import axios from '@/network'
import {msg, encryptRSA, encryptAES, generateRandomAESKey} from '@/utils/Utils'
import {ElMessage, ElMessageBox} from 'element-plus'
import type {FormInstance, FormRules} from 'element-plus'
import router from '@/router'
import {headerCellStyle} from "@/assets/css/el.ts";
import {getLangData} from "@/i18n/locale";

const langData = getLangData()

onMounted(() => {
  query()
})

const query = () => {
}

const form = reactive({
  cond1: '',
  cond2: '',
  pageNum: 1,
  pageSize: 10,
  total: 4
})

const data = ref<any>([{
  id: 1,
  name: 'Jack',
  age: 28,
  address: 'New York'
},
  {
    id: 2,
    name: 'Tom',
    age: 32,
    address: 'Los Angeles'
  },
  {
    id: 3,
    name: 'Lucy',
    age: 25,
    address: 'Chicago'
  },
  {
    id: 4,
    name: 'Mike',
    age: 27,
    address: 'Houston'
  }])

const showEditDialog=(row:any)=>{

}

const deleteUser=(row:any)=>{

}

const debounce = (callback: (...args: any[]) => void, delay: number) => {
  let tid: any
  return function (...args: any[]) {
    const ctx = self
    tid && clearTimeout(tid)
    tid = setTimeout(() => {
      callback.apply(ctx, args)
    }, delay)
  }
}

const _ = (window as any).ResizeObserver;
(window as any).ResizeObserver = class ResizeObserver extends _ {
  constructor(callback: (...args: any[]) => void) {
    callback = debounce(callback, 20)
    super(callback)
  }
}

</script>

<template>
  <div class="container">
    <el-page-header :icon="ArrowLeft" @back="router.push({path:'/'})"
                    v-if="!router.currentRoute.value.query.hiddeHeader">
      <template #content>
        <span> demo </span>
      </template>
    </el-page-header>
    <el-divider content-position="left" v-if="!router.currentRoute.value.query.hiddeHeader"></el-divider>
    <el-form :model="form" size="small" label-position="right" inline-message inline>
      <el-form-item label="Search Cond1" prop="cond1">
        <el-input v-model="form.cond1" :placeholder="langData.formInputPlaceholder + '...'" type="text" clearable/>
      </el-form-item>
      <el-form-item label="Search Cond2" prop="cond2">
        <el-input v-model="form.cond2" :placeholder="langData.formInputPlaceholder + '...'" type="text" clearable/>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" size="small" @click="query()">Search</el-button>
      </el-form-item>
    </el-form>
    <el-table :data="data" style="width: 100%" :border="true" table-layout="fixed" :stripe="true"
              size="small" :highlight-current-row="true" :header-cell-style="headerCellStyle">
      <el-table-column fixed="left" label="Operation" width="180" header-align="center" align="center">
        <template #default="scope">
          <el-button link type="primary" size="small" @click="showEditDialog(scope.row)">edit
          </el-button>
          <el-popconfirm :title="langData.confirmDelete" @confirm="deleteUser(scope.row)"
                         icon-color="red"
                         confirm-button-type="danger">
            <template #reference>
              <el-button link type="danger" size="small">delete
              </el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
      <el-table-column prop="id" label="ID" :show-overflow-tooltip="true" header-align="center"
                       align="center"/>
      <el-table-column prop="name" label="Name" :show-overflow-tooltip="true" header-align="center"
                       align="center"/>
      <el-table-column prop="age" label="Age" :show-overflow-tooltip="true" header-align="center"
                       align="center"/>
      <el-table-column prop="address" label="Address" :show-overflow-tooltip="true" header-align="center"
                       align="center"/>
    </el-table>
    <el-pagination class="page" v-model:page-size="form.pageSize" v-model:current-page="form.pageNum"
                   layout="->, total, sizes, prev, pager, next, jumper" v-model:total="form.total"
                   @size-change="query()"
                   @current-change="query()" @prev-click="query()" @next-click="query()"
                   size="small" :background="true"
                   :page-sizes="[5, 10, 20, 50, 100]"/>
  </div>
</template>

<style scoped>
.container {
  flex-grow: 1;
  padding: 20px 2%;
  overflow: auto;
  width: 96%;
}
</style>