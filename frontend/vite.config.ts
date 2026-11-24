import { defineConfig } from 'vite'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({mode}) => ({
  plugins: [vue(), {name:'static-preview-entry', enforce:'pre', transformIndexHtml: { order:'pre', handler(html:string){return mode==='pages' ? html.replace('/src/main.ts','/src/preview/main.ts') : html} } }, {
    name:'published-measurements',
    generateBundle(){
      if(mode!=='pages')return
      const included:string[]=[]
      for(const id of ['sql-matrix','uniform','hot']){
        const path=resolve(process.cwd(),'../performance/results',id+'.json')
        if(!existsSync(path))continue
        const contents=readFileSync(path,'utf8')
        JSON.parse(contents)
        this.emitFile({type:'asset',fileName:'measurements/'+id+'.json',source:contents})
        included.push(id)
      }
      this.emitFile({type:'asset',fileName:'measurements/index.json',source:JSON.stringify(included)})
    }
  }],
  server: { host: '127.0.0.1', proxy: { '/api': 'http://127.0.0.1:8080' } },
  build: { chunkSizeWarningLimit: 850, rollupOptions: { output: { manualChunks: { charts: ['echarts'], framework: ['vue', 'vue-router', 'pinia'] } } } },
}))
