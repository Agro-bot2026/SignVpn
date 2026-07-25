package com.ghostvpn

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter

class PagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
    private val fragments = listOf(SshFragment(), LogFragment())
    private val titles = listOf("SSH", "LOG")

    override fun getCount() = fragments.size
    override fun getItem(pos: Int) = fragments[pos]
    override fun getPageTitle(pos: Int) = titles[pos]
    fun getSshFragment() = fragments[0] as SshFragment
    fun getLogFragment() = fragments[1] as LogFragment
}
